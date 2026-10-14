package com.aegis.provider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aegis.api.GatewayException;
import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatCompletionResponse;
import com.aegis.config.AegisProperties;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sends each request to the highest-priority healthy provider that serves its model. Each
 * provider has its own circuit breaker; transient errors are retried with exponential backoff
 * and jitter, and then the next provider is tried.
 */
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private static final Predicate<Throwable> TRANSIENT =
            e -> e instanceof ProviderException pe && pe.isTransient();
    private static final Predicate<Throwable> FAILOVER =
            e -> e instanceof CallNotPermittedException || (e instanceof ProviderException pe && pe.warrantsFailover());

    private final List<Route> routes;

    public ProviderRouter(List<Route> routesByPriority) {
        if (routesByPriority.isEmpty()) {
            throw new IllegalArgumentException("At least one provider is required");
        }
        this.routes = List.copyOf(routesByPriority);
    }

    public static Route route(LlmProvider provider, AegisProperties.Provider config, AegisProperties.Resilience resilience) {
        return route(provider, config, resilience, CircuitBreakerRegistry.ofDefaults());
    }

    /** Creates the provider's circuit breaker in {@code registry}, so it can be exported as metrics. */
    public static Route route(LlmProvider provider, AegisProperties.Provider config, AegisProperties.Resilience resilience,
                              CircuitBreakerRegistry registry) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(config.name(), CircuitBreakerConfig.custom()
                .failureRateThreshold(resilience.failureRateThreshold())
                .slidingWindowSize(resilience.slidingWindowSize())
                .minimumNumberOfCalls(resilience.minimumNumberOfCalls())
                .waitDurationInOpenState(resilience.openStateDuration())
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(FAILOVER)
                .build());
        Retry retry = Retry.of(config.name(), RetryConfig.custom()
                .maxAttempts(resilience.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(resilience.initialBackoff(), 2.0, 0.5))
                .retryOnException(TRANSIENT)
                .build());
        return new Route(provider, config, circuitBreaker, retry);
    }

    public Mono<RoutedResponse> route(ChatCompletionRequest request) {
        return Mono.defer(() -> attempt(candidates(request.getModel()), 0, request, null));
    }

    /**
     * Opens a stream on the first provider that produces a first chunk. Once a chunk has been
     * sent to the client there is no failover, because the client has already seen part of an answer.
     */
    public Mono<RoutedStream> stream(ChatCompletionRequest request) {
        return Mono.defer(() -> attemptStream(candidates(request.getModel()), 0, request, null));
    }

    public List<ProviderHealth> health() {
        return routes.stream().map(route -> {
            CircuitBreaker.Metrics metrics = route.circuitBreaker().getMetrics();
            return new ProviderHealth(route.config().name(), route.config().type().name().toLowerCase().replace('_', '-'),
                    route.config().priority(), route.config().models(), route.circuitBreaker().getState().name(),
                    metrics.getFailureRate(), metrics.getNumberOfBufferedCalls(), metrics.getNumberOfFailedCalls(),
                    metrics.getNumberOfNotPermittedCalls());
        }).toList();
    }

    private List<Route> candidates(String model) {
        List<Route> candidates = routes.stream().filter(route -> route.config().serves(model)).toList();
        if (candidates.isEmpty()) {
            throw GatewayException.invalidRequest("model_not_supported",
                    "No configured provider serves model '" + model + "'.");
        }
        return candidates;
    }

    private Mono<RoutedResponse> attempt(List<Route> candidates, int index, ChatCompletionRequest request, Throwable last) {
        if (index >= candidates.size()) {
            return Mono.error(exhausted(last));
        }
        Route route = candidates.get(index);
        ChatCompletionRequest upstream = request.withModel(route.config().upstreamModel(request.getModel()));
        return Mono.defer(() -> route.provider().chatCompletion(upstream))
                .transformDeferred(CircuitBreakerOperator.of(route.circuitBreaker()))
                .transformDeferred(RetryOperator.of(route.retry()))
                .map(response -> new RoutedResponse(route.config().name(), response, index > 0))
                .onErrorResume(FAILOVER, error -> {
                    log.warn("Provider '{}' failed ({}); trying next provider", route.config().name(), error.getMessage());
                    return attempt(candidates, index + 1, request, error);
                });
    }

    private Mono<RoutedStream> attemptStream(List<Route> candidates, int index, ChatCompletionRequest request, Throwable last) {
        if (index >= candidates.size()) {
            return Mono.error(exhausted(last));
        }
        Route route = candidates.get(index);
        ChatCompletionRequest upstream = request.withModel(route.config().upstreamModel(request.getModel()));
        AtomicReference<Disposable> connection = new AtomicReference<>();
        // Replay lets us inspect the first chunk (to decide on failover) and still hand the whole
        // stream to the client from the start, with a single upstream call.
        Flux<String> shared = Flux.defer(() -> route.provider().streamChatCompletion(upstream))
                .transformDeferred(CircuitBreakerOperator.of(route.circuitBreaker()))
                .replay()
                .autoConnect(1, connection::set);
        Flux<String> chunks = shared.doOnCancel(() -> {
            Disposable upstreamConnection = connection.get();
            if (upstreamConnection != null) {
                upstreamConnection.dispose();
            }
        });
        return shared.next()
                .map(first -> new RoutedStream(route.config().name(), chunks, index > 0))
                .defaultIfEmpty(new RoutedStream(route.config().name(), Flux.empty(), index > 0))
                .onErrorResume(FAILOVER, error -> {
                    log.warn("Provider '{}' failed to start a stream ({}); trying next provider",
                            route.config().name(), error.getMessage());
                    return attemptStream(candidates, index + 1, request, error);
                });
    }

    private static Throwable exhausted(Throwable last) {
        if (last instanceof CallNotPermittedException open) {
            return new ProviderException(open.getCausingCircuitBreakerName(),
                    "All providers are unavailable (circuit breaker open for '" + open.getCausingCircuitBreakerName() + "')",
                    open);
        }
        return last != null ? last : new IllegalStateException("No provider attempted");
    }

    public record Route(LlmProvider provider, AegisProperties.Provider config, CircuitBreaker circuitBreaker, Retry retry) {
    }

    /** @param failedOver whether a lower-priority provider served the request */
    public record RoutedResponse(String provider, ChatCompletionResponse response, boolean failedOver) {
    }

    public record RoutedStream(String provider, Flux<String> chunks, boolean failedOver) {
    }

    public record ProviderHealth(String name, String type, int priority, List<String> models, String circuitState,
                                 float failureRate, int bufferedCalls, int failedCalls, long notPermittedCalls) {
    }
}

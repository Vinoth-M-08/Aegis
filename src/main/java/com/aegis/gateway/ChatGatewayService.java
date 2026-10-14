package com.aegis.gateway;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aegis.api.AegisHeaders;
import com.aegis.api.GatewayException;
import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatCompletionResponse;
import com.aegis.api.dto.Usage;
import com.aegis.auth.ApiKeyAuthenticator;
import com.aegis.auth.TenantContext;
import com.aegis.budget.BudgetService;
import com.aegis.budget.RateLimiter;
import com.aegis.budget.TokenEstimator;
import com.aegis.cache.SemanticCache;
import com.aegis.config.AegisProperties;
import com.aegis.guard.GuardVerdict;
import com.aegis.guard.InjectionMode;
import com.aegis.guard.PromptInjectionDetector;
import com.aegis.provider.ProviderException;
import com.aegis.provider.ProviderRouter;
import com.aegis.provider.ProviderTimeoutException;
import com.aegis.redaction.PiiRedactor;
import com.aegis.redaction.RedactionSession;
import com.aegis.redaction.StreamingRestorer;
import com.aegis.usage.AuditLog;
import com.aegis.usage.PricingService;
import com.aegis.usage.UsageRecord;
import com.aegis.usage.UsageRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.observation.ObservationRegistry;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The request pipeline: authenticate, rate limit, guard, reserve budget, redact, consult the
 * semantic cache, route to a provider, restore, settle the budget and record usage.
 */
@Service
public class ChatGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ChatGatewayService.class);
    private static final Duration CACHE_LOOKUP_TIMEOUT = Duration.ofSeconds(2);

    static final String CACHE_HIT = "HIT";
    static final String CACHE_MISS = "MISS";
    static final String CACHE_BYPASS = "BYPASS";

    private final ApiKeyAuthenticator authenticator;
    private final RateLimiter rateLimiter;
    private final BudgetService budgets;
    private final TokenEstimator estimator;
    private final PromptInjectionDetector injectionDetector;
    private final PiiRedactor redactor;
    private final SemanticCache cache;
    private final ProviderRouter router;
    private final PricingService pricing;
    private final UsageRecorder usage;
    private final AuditLog audit;
    private final AegisProperties.Security security;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observations;

    public ChatGatewayService(ApiKeyAuthenticator authenticator, RateLimiter rateLimiter, BudgetService budgets,
                              TokenEstimator estimator, PromptInjectionDetector injectionDetector, PiiRedactor redactor,
                              SemanticCache cache, ProviderRouter router, PricingService pricing, UsageRecorder usage,
                              AuditLog audit, AegisProperties properties, ObjectMapper objectMapper,
                              ObservationRegistry observations) {
        this.authenticator = authenticator;
        this.rateLimiter = rateLimiter;
        this.budgets = budgets;
        this.estimator = estimator;
        this.injectionDetector = injectionDetector;
        this.redactor = redactor;
        this.cache = cache;
        this.router = router;
        this.pricing = pricing;
        this.usage = usage;
        this.audit = audit;
        this.security = properties.security();
        this.objectMapper = objectMapper;
        this.observations = observations;
    }

    public Mono<GatewayResponse> complete(String authorization, ChatCompletionRequest request, String requestId) {
        long start = System.nanoTime();
        return prepare(authorization, request, requestId, start, false)
                .flatMap(p -> lookupCacheOptional(p)
                        .flatMap(lookup -> lookup.flatMap(SemanticCache.Lookup::hit).isPresent()
                                ? serveFromCache(p, lookup.get().hit().get())
                                : callProvider(p, lookup.orElse(null)))
                        .onErrorResume(error -> !(error instanceof Recorded),
                                error -> failAfterReservation(p, error)));
    }

    public Mono<GatewayStream> stream(String authorization, ChatCompletionRequest request, String requestId) {
        long start = System.nanoTime();
        return prepare(authorization, request, requestId, start, true)
                .flatMap(p -> lookupCacheOptional(p)
                        .flatMap(lookup -> lookup.flatMap(SemanticCache.Lookup::hit).isPresent()
                                ? streamFromCache(p, lookup.get().hit().get())
                                : streamFromProvider(p))
                        .onErrorResume(error -> !(error instanceof Recorded),
                                error -> failAfterReservation(p, error)));
    }

    // ---- shared preparation -------------------------------------------------------------------

    private Mono<Prepared> prepare(String authorization, ChatCompletionRequest request, String requestId,
                                   long start, boolean streamed) {
        return authenticator.authenticate(authorization).flatMap(ctx -> {
            Map<String, String> headers = new LinkedHashMap<>();
            if (!ctx.allowsModel(request.getModel())) {
                return reject(ctx, request, requestId, start, streamed, "blocked",
                        GatewayException.forbidden("model_not_allowed",
                                "Model '" + request.getModel() + "' is not allowed for this API key."));
            }
            return rateLimiter.check(ctx)
                    .onErrorResume(GatewayException.class,
                            e -> reject(ctx, request, requestId, start, streamed, "rate_limited", e))
                    .then(Mono.defer(() -> guard(ctx, request, requestId, start, streamed, headers)))
                    .then(Mono.defer(() -> budgets.reserve(ctx, estimator.estimateTotal(request))
                            .name("aegis.budget.reserve").tap(Micrometer.observation(observations))
                            .onErrorResume(GatewayException.class,
                                    e -> reject(ctx, request, requestId, start, streamed, "budget_exceeded", e))))
                    .map(reservation -> {
                        RedactionSession session = redactor.newSession();
                        boolean redact = security.piiRedaction() && ctx.tenant().piiRedaction();
                        if (redact) {
                            session.redact(request);
                            if (session.total() > 0) {
                                headers.put(AegisHeaders.REDACTIONS, Integer.toString(session.total()));
                                audit.record(requestId, ctx.tenant().id(), ctx.apiKey().id(), AuditLog.PII_REDACTED,
                                        Map.of("counts", session.counts()));
                            }
                        }
                        return new Prepared(ctx, request, requestId, start, streamed, reservation, session, redact, headers);
                    });
        });
    }

    private Mono<Void> guard(TenantContext ctx, ChatCompletionRequest request, String requestId, long start,
                             boolean streamed, Map<String, String> headers) {
        InjectionMode mode = security.injectionGuard() == InjectionMode.OFF ? InjectionMode.OFF : ctx.tenant().injectionMode();
        if (mode == InjectionMode.OFF) {
            return Mono.empty();
        }
        GuardVerdict verdict = injectionDetector.inspect(request);
        if (!verdict.detected()) {
            return Mono.empty();
        }
        Map<String, Object> details = Map.of("score", verdict.score(), "rules", verdict.matchedRules(),
                "model", request.getModel());
        if (mode == InjectionMode.BLOCK) {
            audit.record(requestId, ctx.tenant().id(), ctx.apiKey().id(), AuditLog.INJECTION_BLOCKED, details);
            return reject(ctx, request, requestId, start, streamed, "blocked",
                    GatewayException.invalidRequest("prompt_injection_detected",
                            "The request was blocked because it looks like a prompt-injection attempt."));
        }
        audit.record(requestId, ctx.tenant().id(), ctx.apiKey().id(), AuditLog.INJECTION_FLAGGED, details);
        headers.put(AegisHeaders.GUARD, "flagged");
        return Mono.empty();
    }

    private Mono<Optional<SemanticCache.Lookup>> lookupCacheOptional(Prepared p) {
        return lookupCache(p).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** Empty when the cache does not apply. Cache trouble is logged and treated as a miss. */
    private Mono<SemanticCache.Lookup> lookupCache(Prepared p) {
        if (!cache.enabledFor(p.ctx()) || !cache.isCacheable(p.request())) {
            return Mono.empty();
        }
        return cache.lookup(p.ctx(), p.request())
                .name("aegis.cache.lookup").tap(Micrometer.observation(observations))
                .timeout(CACHE_LOOKUP_TIMEOUT)
                .onErrorResume(error -> {
                    log.warn("Semantic cache lookup failed for {}: {}", p.requestId(), error.toString());
                    return Mono.empty();
                });
    }

    // ---- non-streaming ------------------------------------------------------------------------

    private Mono<GatewayResponse> serveFromCache(Prepared p, SemanticCache.Hit hit) {
        ObjectNode cached = hit.response().deepCopy();
        if (p.restore()) {
            cached.set("choices", p.session().restoreTree(cached.get("choices")));
        }
        cached.put("id", "chatcmpl-aegis-" + p.requestId());
        ChatCompletionResponse response = treeToResponse(cached);
        Tokens original = tokens(response.getUsage(), p, MessageContent.of(response));
        BigDecimal saved = pricing.cost(p.request().getModel(), original.prompt(), original.completion());
        return budgets.settle(p.reservation(), 0).map(Optional::of).defaultIfEmpty(Optional.empty())
                .map(remaining -> {
                    record(p, "cache", "success", CACHE_HIT, 0, 0, BigDecimal.ZERO, saved, null);
                    Map<String, String> headers = responseHeaders(p, "cache", CACHE_HIT, remaining, false);
                    headers.put(AegisHeaders.CACHE_SIMILARITY, String.format(java.util.Locale.ROOT, "%.4f", hit.similarity()));
                    return new GatewayResponse(response, headers);
                });
    }

    private Mono<GatewayResponse> callProvider(Prepared p, SemanticCache.Lookup lookup) {
        String cacheStatus = lookup != null ? CACHE_MISS : CACHE_BYPASS;
        return router.route(p.request())
                .name("aegis.provider.call").tap(Micrometer.observation(observations))
                .flatMap(routed -> {
                    ChatCompletionResponse response = routed.response();
                    if (lookup != null) {
                        storeInCache(p, lookup, response);
                    }
                    if (p.restore() && response.getChoices() != null) {
                        response.setChoices(p.session().restoreTree(response.getChoices()));
                    }
                    Tokens tokens = tokens(response.getUsage(), p, MessageContent.of(response));
                    BigDecimal cost = pricing.cost(p.request().getModel(), tokens.prompt(), tokens.completion());
                    return budgets.settle(p.reservation(), tokens.total()).map(Optional::of).defaultIfEmpty(Optional.empty())
                            .map(remaining -> {
                                record(p, routed.provider(), "success", cacheStatus, tokens.prompt(), tokens.completion(),
                                        cost, BigDecimal.ZERO, null);
                                return new GatewayResponse(response,
                                        responseHeaders(p, routed.provider(), cacheStatus, remaining, routed.failedOver()));
                            });
                });
    }

    private void storeInCache(Prepared p, SemanticCache.Lookup lookup, ChatCompletionResponse response) {
        ObjectNode tree = objectMapper.valueToTree(response);
        if (p.redact()) {
            // The answer may contain PII the prompt did not; cached entries must never hold any.
            tree.set("choices", p.session().redactTree(tree.get("choices")));
        }
        cache.store(p.ctx(), lookup, tree)
                .doOnError(e -> log.warn("Could not store cache entry for {}: {}", p.requestId(), e.toString()))
                .onErrorComplete()
                .subscribe();
    }

    // ---- streaming ----------------------------------------------------------------------------

    private Mono<GatewayStream> streamFromCache(Prepared p, SemanticCache.Hit hit) {
        JsonNode cached = hit.response();
        JsonNode choices = p.restore() ? p.session().restoreTree(cached.get("choices")) : cached.get("choices");
        String content = choices.path(0).path("message").path("content").asText("");
        String finish = choices.path(0).path("finish_reason").asText("stop");
        String id = "chatcmpl-aegis-" + p.requestId();
        long created = System.currentTimeMillis() / 1000;
        String model = p.request().getModel();

        Tokens original = tokens(treeToResponse(cached).getUsage(), p, content);
        BigDecimal saved = pricing.cost(model, original.prompt(), original.completion());
        boolean clientWantsUsage = clientWantsUsage(p.request());

        List<String> events = new java.util.ArrayList<>();
        events.add(chunk(id, created, model, objectMapper.createObjectNode().put("role", "assistant"), null));
        events.add(chunk(id, created, model, objectMapper.createObjectNode().put("content", content), null));
        events.add(chunk(id, created, model, objectMapper.createObjectNode(), finish));
        if (clientWantsUsage) {
            ObjectNode usageChunk = chunkNode(id, created, model);
            usageChunk.putArray("choices");
            usageChunk.set("usage", cached.get("usage"));
            events.add(usageChunk.toString());
        }
        events.add("[DONE]");

        return budgets.settle(p.reservation(), 0).map(Optional::of).defaultIfEmpty(Optional.empty())
                .map(remaining -> {
                    record(p, "cache", "success", CACHE_HIT, 0, 0, BigDecimal.ZERO, saved, null);
                    return new GatewayStream(Flux.fromIterable(events),
                            responseHeaders(p, "cache", CACHE_HIT, remaining, false));
                });
    }

    private Mono<GatewayStream> streamFromProvider(Prepared p) {
        boolean clientWantsUsage = clientWantsUsage(p.request());
        ChatCompletionRequest upstream = p.request().withModel(p.request().getModel());
        Map<String, Object> streamOptions = new HashMap<>();
        if (upstream.getAdditionalProperties().get("stream_options") instanceof Map<?, ?> existing) {
            existing.forEach((k, v) -> streamOptions.put(String.valueOf(k), v));
        }
        // Always ask for usage so the budget settles on real numbers; strip it again if the client did not ask.
        streamOptions.put("include_usage", true);
        upstream.setAdditionalProperty("stream_options", streamOptions);

        return router.stream(upstream).map(routed -> {
            Map<Integer, StreamingRestorer> restorers = new HashMap<>();
            AtomicReference<Usage> reportedUsage = new AtomicReference<>();
            AtomicLong streamedChars = new AtomicLong();
            AtomicBoolean finished = new AtomicBoolean();

            Flux<String> events = routed.chunks()
                    .concatMap(data -> Mono.justOrEmpty(processChunk(p, data, clientWantsUsage, restorers,
                            reportedUsage, streamedChars)))
                    .concatWith(Mono.defer(() -> {
                        if (finished.compareAndSet(false, true)) {
                            finishStream(p, routed.provider(), reportedUsage.get(), streamedChars.get(), "success", null);
                        }
                        return Mono.just("[DONE]");
                    }))
                    .onErrorResume(error -> {
                        if (finished.compareAndSet(false, true)) {
                            finishStream(p, routed.provider(), reportedUsage.get(), streamedChars.get(), "error",
                                    errorCode(error));
                        }
                        log.warn("Stream {} from '{}' failed: {}", p.requestId(), routed.provider(), error.toString());
                        return Flux.just(errorEvent(error), "[DONE]");
                    })
                    .doOnCancel(() -> {
                        if (finished.compareAndSet(false, true)) {
                            finishStream(p, routed.provider(), reportedUsage.get(), streamedChars.get(), "cancelled", null);
                        }
                    });
            return new GatewayStream(events,
                    responseHeaders(p, routed.provider(), CACHE_BYPASS, p.reservation().remaining(), routed.failedOver()));
        });
    }

    private String processChunk(Prepared p, String data, boolean clientWantsUsage, Map<Integer, StreamingRestorer> restorers,
                                AtomicReference<Usage> reportedUsage, AtomicLong streamedChars) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(data);
        } catch (JsonProcessingException e) {
            return data;
        }
        if (!(parsed instanceof ObjectNode chunk)) {
            return data;
        }
        JsonNode usageNode = chunk.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            reportedUsage.set(objectMapper.convertValue(usageNode, Usage.class));
            if (!clientWantsUsage) {
                chunk.remove("usage");
                if (chunk.path("choices").isEmpty()) {
                    return null;
                }
            }
        }
        for (JsonNode choice : chunk.path("choices")) {
            if (!(choice.get("delta") instanceof ObjectNode delta)) {
                continue;
            }
            int index = choice.path("index").asInt(0);
            JsonNode content = delta.get("content");
            boolean finishing = !choice.path("finish_reason").isNull() && choice.has("finish_reason");
            if (content != null && content.isTextual()) {
                streamedChars.addAndGet(content.asText().length());
            }
            if (!p.restore()) {
                continue;
            }
            StreamingRestorer restorer = restorers.computeIfAbsent(index, i -> new StreamingRestorer(p.session()));
            String text = content != null && content.isTextual() ? restorer.accept(content.asText()) : "";
            if (finishing) {
                text += restorer.flush();
            }
            if (content != null || !text.isEmpty()) {
                delta.put("content", text);
            }
        }
        return chunk.toString();
    }

    private void finishStream(Prepared p, String provider, Usage reported, long streamedChars, String outcome, String errorCode) {
        Tokens tokens = tokens(reported, p, null);
        long prompt = tokens.prompt();
        long completion = reported != null && reported.getCompletionTokens() != null
                ? reported.getCompletionTokens() : (streamedChars + 3) / 4;
        BigDecimal cost = pricing.cost(p.request().getModel(), prompt, completion);
        budgets.settle(p.reservation(), prompt + completion)
                .doOnError(e -> log.warn("Could not settle budget for {}: {}", p.requestId(), e.toString()))
                .onErrorComplete()
                .subscribe();
        record(p, provider, outcome, CACHE_BYPASS, prompt, completion, cost, BigDecimal.ZERO, errorCode);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private <T> Mono<T> failAfterReservation(Prepared p, Throwable error) {
        return budgets.release(p.reservation())
                .onErrorResume(e -> {
                    log.warn("Could not release budget for {}: {}", p.requestId(), e.toString());
                    return Mono.empty();
                })
                .then(Mono.fromRunnable(() -> record(p, providerOf(error), "error", CACHE_BYPASS, 0, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, errorCode(error))))
                .then(Mono.error(error));
    }

    private <T> Mono<T> reject(TenantContext ctx, ChatCompletionRequest request, String requestId, long start,
                               boolean streamed, String outcome, GatewayException error) {
        usage.record(new UsageRecord(requestId, ctx.tenant().id(), ctx.apiKey().id(), request.getModel(), null, outcome,
                CACHE_BYPASS, streamed, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, elapsedMs(start), error.getCode()));
        return Mono.error(new RecordedGatewayException(error));
    }

    private void record(Prepared p, String provider, String outcome, String cacheStatus, long prompt, long completion,
                        BigDecimal cost, BigDecimal saved, String errorCode) {
        usage.record(new UsageRecord(p.requestId(), p.ctx().tenant().id(), p.ctx().apiKey().id(), p.request().getModel(),
                provider, outcome, cacheStatus, p.streamed(), prompt, completion, cost, saved, elapsedMs(p.start()), errorCode));
    }

    private Map<String, String> responseHeaders(Prepared p, String provider, String cacheStatus, Optional<Long> remaining,
                                                boolean failedOver) {
        return responseHeaders(p, provider, cacheStatus, remaining.orElse(null), failedOver);
    }

    private Map<String, String> responseHeaders(Prepared p, String provider, String cacheStatus, Long remaining,
                                                boolean failedOver) {
        Map<String, String> headers = new LinkedHashMap<>(p.headers());
        headers.put(AegisHeaders.PROVIDER, provider);
        headers.put(AegisHeaders.CACHE, cacheStatus);
        if (remaining != null) {
            headers.put(AegisHeaders.TOKENS_REMAINING, Long.toString(remaining));
        }
        if (failedOver) {
            headers.put(AegisHeaders.FAILOVER, "true");
        }
        return headers;
    }

    private ChatCompletionResponse treeToResponse(JsonNode tree) {
        try {
            return objectMapper.treeToValue(tree, ChatCompletionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt cached response", e);
        }
    }

    private ObjectNode chunkNode(String id, long created, String model) {
        return objectMapper.createObjectNode()
                .put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model);
    }

    private String chunk(String id, long created, String model, ObjectNode delta, String finishReason) {
        ObjectNode chunk = chunkNode(id, created, model);
        ObjectNode choice = chunk.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return chunk.toString();
    }

    private String errorEvent(Throwable error) {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("error")
                .put("message", error instanceof ProviderException || error instanceof GatewayException
                        ? error.getMessage() : "The stream was interrupted")
                .put("type", "provider_error")
                .put("code", errorCode(error));
        return body.toString();
    }

    private static boolean clientWantsUsage(ChatCompletionRequest request) {
        return request.getAdditionalProperties().get("stream_options") instanceof Map<?, ?> options
                && Boolean.TRUE.equals(options.get("include_usage"));
    }

    private static String providerOf(Throwable error) {
        return error instanceof ProviderException pe ? pe.getProvider() : null;
    }

    private static String errorCode(Throwable error) {
        if (error instanceof GatewayException ge) {
            return ge.getCode();
        }
        if (error instanceof ProviderTimeoutException) {
            return "provider_timeout";
        }
        if (error instanceof ProviderException) {
            return "provider_error";
        }
        return "internal_error";
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Marks errors whose usage record was already written, so they are not recorded twice. */
    interface Recorded {
    }

    static final class RecordedGatewayException extends GatewayException implements Recorded {

        RecordedGatewayException(GatewayException original) {
            super(original.getStatus(), original.getType(), original.getCode(), original.getMessage(), original.getHeaders());
        }
    }

    record Prepared(TenantContext ctx, ChatCompletionRequest request, String requestId, long start, boolean streamed,
                    BudgetService.Reservation reservation, RedactionSession session, boolean redact,
                    Map<String, String> headers) {

        boolean restore() {
            return redact && session.total() > 0;
        }
    }

    /** Uses the provider's counts, falling back to estimates when it reports none. */
    private Tokens tokens(Usage usage, Prepared p, String completionText) {
        long prompt = usage != null && usage.getPromptTokens() != null
                ? usage.getPromptTokens() : estimator.estimatePrompt(p.request());
        long completion = usage != null && usage.getCompletionTokens() != null
                ? usage.getCompletionTokens()
                : completionText != null ? TokenEstimator.estimateText(completionText) : 0;
        return new Tokens(prompt, completion);
    }

    record Tokens(long prompt, long completion) {

        long total() {
            return prompt + completion;
        }
    }

    /** Concatenated text of all choices, for estimating completion tokens. */
    private static final class MessageContent {
        static String of(ChatCompletionResponse response) {
            if (response.getChoices() == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            response.getChoices().forEach(choice -> sb.append(choice.path("message").path("content").asText("")));
            return sb.toString();
        }
    }

    public record GatewayResponse(ChatCompletionResponse body, Map<String, String> headers) {
    }

    /** @param events SSE data payloads, ending with {@code [DONE]} */
    public record GatewayStream(Flux<String> events, Map<String, String> headers) {
    }
}

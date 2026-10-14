package com.aegis.budget;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.aegis.api.GatewayException;
import com.aegis.auth.TenantContext;
import com.aegis.config.AegisProperties;

import reactor.core.publisher.Mono;

/**
 * Requests-per-minute limit per tenant using a sliding window.
 */
@Service
public class RateLimiter {

    private static final long WINDOW_MS = Duration.ofMinutes(1).toMillis();

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(new ClassPathResource("lua/rate_limit.lua"), List.class);

    private final ReactiveStringRedisTemplate redis;
    private final AegisProperties.Budgets defaults;
    private final Clock clock;

    public RateLimiter(ReactiveStringRedisTemplate redis, AegisProperties properties, Clock clock) {
        this.redis = redis;
        this.defaults = properties.budgets();
        this.clock = clock;
    }

    public Mono<Void> check(TenantContext ctx) {
        Integer limit = ctx.tenant().requestsPerMinute() != null
                ? ctx.tenant().requestsPerMinute()
                : defaults.defaultRequestsPerMinute();
        if (limit == null || limit <= 0) {
            return Mono.empty();
        }
        String key = "aegis:rl:tenant:" + ctx.tenant().id();
        List<String> args = List.of(Long.toString(clock.millis()), Long.toString(WINDOW_MS),
                Integer.toString(limit), UUID.randomUUID().toString());
        return redis.execute(SCRIPT, List.of(key), args)
                .next()
                .flatMap(result -> {
                    List<?> values = (List<?>) result;
                    if (((Number) values.get(0)).longValue() == 1) {
                        return Mono.<Void>empty();
                    }
                    long retryAfterSeconds = Math.max(1, (((Number) values.get(2)).longValue() + 999) / 1000);
                    return Mono.error(new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error",
                            "rate_limit_exceeded",
                            "Rate limit of " + limit + " requests per minute exceeded. Retry in " + retryAfterSeconds + "s.",
                            Map.of("Retry-After", Long.toString(retryAfterSeconds))));
                });
    }
}

package com.aegis.budget;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.aegis.api.GatewayException;
import com.aegis.auth.Tenant;
import com.aegis.auth.TenantContext;
import com.aegis.config.AegisProperties;

import reactor.core.publisher.Mono;

/**
 * Daily and monthly token budgets, enforced with reserve-then-settle Lua scripts so concurrent
 * requests cannot overspend.
 */
@Service
public class BudgetService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final long DAY_TTL_SECONDS = Duration.ofDays(2).toSeconds();
    private static final long MONTH_TTL_SECONDS = Duration.ofDays(35).toSeconds();

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisScript.of(new ClassPathResource("lua/budget_reserve.lua"), List.class);
    private static final RedisScript<Long> SETTLE = RedisScript.of(new ClassPathResource("lua/budget_settle.lua"), Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final AegisProperties.Budgets defaults;
    private final Clock clock;

    public BudgetService(ReactiveStringRedisTemplate redis, AegisProperties properties, Clock clock) {
        this.redis = redis;
        this.defaults = properties.budgets();
        this.clock = clock;
    }

    public Mono<Reservation> reserve(TenantContext ctx, long tokens) {
        List<Counter> counters = counters(ctx);
        List<String> keys = counters.stream().map(Counter::key).toList();
        List<String> args = new ArrayList<>();
        args.add(Long.toString(tokens));
        for (Counter counter : counters) {
            args.add(Long.toString(counter.limit() == null ? -1 : counter.limit()));
            args.add(Long.toString(counter.ttlSeconds()));
        }
        return redis.execute(RESERVE, keys, args)
                .next()
                .map(result -> {
                    List<?> values = (List<?>) result;
                    long allowed = ((Number) values.get(0)).longValue();
                    if (allowed == 0) {
                        Counter exceeded = counters.get(((Number) values.get(1)).intValue() - 1);
                        long available = Math.max(0, ((Number) values.get(2)).longValue());
                        throw budgetExceeded(exceeded, tokens, available);
                    }
                    long remaining = ((Number) values.get(2)).longValue();
                    return new Reservation(keys, tokens, remaining < 0 ? null : remaining);
                });
    }

    /** Replaces the reserved amount with the actual usage. Returns the updated remaining budget, if limited. */
    public Mono<Long> settle(Reservation reservation, long actualTokens) {
        long delta = actualTokens - reservation.tokens();
        Long remaining = reservation.remaining() == null ? null : Math.max(0, reservation.remaining() - delta);
        if (delta == 0) {
            return Mono.justOrEmpty(remaining);
        }
        return redis.execute(SETTLE, reservation.keys(), List.of(Long.toString(delta)))
                .then(Mono.justOrEmpty(remaining));
    }

    /** Gives back the whole reservation, e.g. when the provider call failed. */
    public Mono<Void> release(Reservation reservation) {
        return settle(reservation, 0).then();
    }

    /** Current usage for the admin API. */
    public Mono<BudgetUsage> usage(Tenant tenant) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String dayKey = tenantKey(tenant, "day", DAY.format(today));
        String monthKey = tenantKey(tenant, "month", MONTH.format(today));
        return redis.opsForValue().multiGet(List.of(dayKey, monthKey))
                .map(values -> new BudgetUsage(
                        parse(values.get(0)), dailyLimit(tenant),
                        parse(values.get(1)), monthlyLimit(tenant)));
    }

    private List<Counter> counters(TenantContext ctx) {
        Tenant tenant = ctx.tenant();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Counter> counters = new ArrayList<>();
        counters.add(new Counter(tenantKey(tenant, "day", DAY.format(today)), dailyLimit(tenant), DAY_TTL_SECONDS,
                "Daily token budget for this tenant"));
        counters.add(new Counter(tenantKey(tenant, "month", MONTH.format(today)), monthlyLimit(tenant),
                MONTH_TTL_SECONDS, "Monthly token budget for this tenant"));
        if (ctx.apiKey().dailyTokenLimit() != null) {
            counters.add(new Counter("aegis:budget:key:" + ctx.apiKey().id() + ":day:" + DAY.format(today),
                    ctx.apiKey().dailyTokenLimit(), DAY_TTL_SECONDS, "Daily token budget for this API key"));
        }
        return counters;
    }

    private Long dailyLimit(Tenant tenant) {
        return tenant.dailyTokenLimit() != null ? tenant.dailyTokenLimit() : defaults.defaultDailyTokens();
    }

    private Long monthlyLimit(Tenant tenant) {
        return tenant.monthlyTokenLimit() != null ? tenant.monthlyTokenLimit() : defaults.defaultMonthlyTokens();
    }

    private static String tenantKey(Tenant tenant, String period, String bucket) {
        return "aegis:budget:tenant:" + tenant.id() + ":" + period + ":" + bucket;
    }

    private static long parse(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }

    private static GatewayException budgetExceeded(Counter counter, long requested, long available) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "insufficient_quota", "budget_exceeded",
                counter.description() + " is exhausted: request needs about " + requested
                        + " tokens, " + available + " remaining.",
                Map.of("X-Aegis-Tokens-Remaining", Long.toString(available)));
    }

    private record Counter(String key, Long limit, long ttlSeconds, String description) {
    }

    /**
     * @param remaining tokens left on the tightest limited counter, or null when unlimited
     */
    public record Reservation(List<String> keys, long tokens, Long remaining) {
    }

    public record BudgetUsage(long dailyUsed, Long dailyLimit, long monthlyUsed, Long monthlyLimit) {
    }
}

package com.aegis.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aegis.TestProperties;
import com.aegis.api.GatewayException;
import com.aegis.auth.ApiKey;
import com.aegis.auth.Tenant;
import com.aegis.auth.TenantContext;
import com.aegis.guard.InjectionMode;

/**
 * Runs the Lua budget and rate-limit scripts against a real Redis. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class BudgetScriptsTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate template;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), java.time.ZoneOffset.UTC);

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        template = new ReactiveStringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void concurrentReservationsNeverOverspend() throws Exception {
        BudgetService budgets = new BudgetService(template, TestProperties.defaults(), clock);
        TenantContext ctx = tenant(1_000L, null);
        AtomicInteger granted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(100);

        for (int i = 0; i < 100; i++) {
            pool.submit(() -> {
                try {
                    budgets.reserve(ctx, 30).block();
                    granted.incrementAndGet();
                } catch (GatewayException rejected) {
                    // over budget
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(granted.get()).isEqualTo(33); // 33 * 30 = 990 <= 1000
        assertThat(budgets.usage(ctx.tenant()).block().dailyUsed()).isEqualTo(990);
    }

    @Test
    void settleReplacesEstimateWithActualUsage() {
        BudgetService budgets = new BudgetService(template, TestProperties.defaults(), clock);
        TenantContext ctx = tenant(1_000L, null);

        BudgetService.Reservation reservation = budgets.reserve(ctx, 400).block();
        assertThat(reservation.remaining()).isEqualTo(600);
        Long remaining = budgets.settle(reservation, 120).block();

        assertThat(remaining).isEqualTo(880);
        assertThat(budgets.usage(ctx.tenant()).block().dailyUsed()).isEqualTo(120);
    }

    @Test
    void keyLimitIsEnforcedAlongsideTenantLimit() {
        BudgetService budgets = new BudgetService(template, TestProperties.defaults(), clock);
        TenantContext ctx = tenant(10_000L, 100L);

        budgets.reserve(ctx, 80).block();

        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> budgets.reserve(ctx, 30).block(), GatewayException.class).getMessage())
                .contains("API key");
    }

    @Test
    void rateLimiterAllowsUpToLimitPerWindow() {
        RateLimiter limiter = new RateLimiter(template, TestProperties.defaults(), clock);
        TenantContext ctx = tenant(null, null, 3);
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            try {
                limiter.check(ctx).block();
                results.add(true);
            } catch (GatewayException e) {
                assertThat(e.getHeaders()).containsKey("Retry-After");
                results.add(false);
            }
        }
        assertThat(results).containsExactly(true, true, true, false, false);
    }

    private static TenantContext tenant(Long dailyLimit, Long keyLimit) {
        return tenant(dailyLimit, keyLimit, null);
    }

    private static TenantContext tenant(Long dailyLimit, Long keyLimit, Integer rpm) {
        UUID id = UUID.randomUUID();
        Tenant tenant = new Tenant(id, "t-" + id, true, dailyLimit, null, rpm, List.of(), InjectionMode.BLOCK,
                true, true, null, Instant.now(), Instant.now());
        ApiKey key = new ApiKey(UUID.randomUUID(), id, "k", "aegis_x", List.of(), keyLimit, Instant.now(), null, null);
        return new TenantContext(tenant, key);
    }
}

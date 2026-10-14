package com.aegis.usage;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.aegis.support.Blocking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Writes requests to the usage ledger and publishes the matching metrics. Recording never fails
 * or delays the client's request: it runs in the background and only logs errors.
 */
@Component
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    public UsageRecorder(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    public void record(UsageRecord record) {
        publishMetrics(record);
        Blocking.run(() -> insert(record))
                .doOnError(e -> log.error("Failed to write usage record {}", record.requestId(), e))
                .onErrorComplete()
                .subscribe();
    }

    void insert(UsageRecord r) {
        jdbc.sql("""
                        INSERT INTO usage_records (request_id, tenant_id, api_key_id, model, provider, outcome, cache_status,
                                                   streamed, prompt_tokens, completion_tokens, total_tokens, cost_usd,
                                                   saved_usd, latency_ms, error_code)
                        VALUES (:requestId, :tenantId, :apiKeyId, :model, :provider, :outcome, :cacheStatus, :streamed,
                                :prompt, :completion, :total, :cost, :saved, :latency, :errorCode)
                        """)
                .param("requestId", r.requestId())
                .param("tenantId", r.tenantId())
                .param("apiKeyId", r.apiKeyId())
                .param("model", r.model())
                .param("provider", r.provider())
                .param("outcome", r.outcome())
                .param("cacheStatus", r.cacheStatus())
                .param("streamed", r.streamed())
                .param("prompt", r.promptTokens())
                .param("completion", r.completionTokens())
                .param("total", r.totalTokens())
                .param("cost", r.costUsd())
                .param("saved", r.savedUsd())
                .param("latency", r.latencyMs())
                .param("errorCode", r.errorCode())
                .update();
    }

    private void publishMetrics(UsageRecord r) {
        String provider = r.provider() != null ? r.provider() : "none";
        String model = r.model() != null ? r.model() : "unknown";
        Timer.builder("aegis.request.latency")
                .description("End-to-end gateway latency")
                .tags("provider", provider, "model", model, "cache", r.cacheStatus(), "outcome", r.outcome())
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters)
                .record(Duration.ofMillis(r.latencyMs()));
        Counter.builder("aegis.tokens").tags("type", "prompt", "provider", provider, "model", model)
                .register(meters).increment(r.promptTokens());
        Counter.builder("aegis.tokens").tags("type", "completion", "provider", provider, "model", model)
                .register(meters).increment(r.completionTokens());
        Counter.builder("aegis.cost.usd").tags("provider", provider, "model", model)
                .register(meters).increment(r.costUsd().doubleValue());
        Counter.builder("aegis.cost.saved.usd").tags("model", model)
                .register(meters).increment(r.savedUsd().doubleValue());
    }
}

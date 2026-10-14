package com.aegis.usage;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Read side of the usage ledger and audit log for the admin API.
 */
@Repository
public class UsageQueries {

    private static final String RANGE = """
             created_at >= :from AND created_at < :to
             AND (CAST(:tenantId AS uuid) IS NULL OR tenant_id = CAST(:tenantId AS uuid))""";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public UsageQueries(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Summary summary(Instant from, Instant to, UUID tenantId) {
        Totals totals = jdbc.sql("""
                        SELECT count(*) AS requests,
                               count(*) FILTER (WHERE outcome = 'success') AS successes,
                               count(*) FILTER (WHERE outcome = 'error') AS errors,
                               count(*) FILTER (WHERE outcome = 'blocked') AS blocked,
                               count(*) FILTER (WHERE outcome IN ('rate_limited', 'budget_exceeded')) AS throttled,
                               count(*) FILTER (WHERE cache_status = 'HIT') AS cache_hits,
                               count(*) FILTER (WHERE cache_status IN ('HIT', 'MISS')) AS cache_lookups,
                               coalesce(sum(prompt_tokens), 0) AS prompt_tokens,
                               coalesce(sum(completion_tokens), 0) AS completion_tokens,
                               coalesce(sum(cost_usd), 0) AS cost_usd,
                               coalesce(sum(saved_usd), 0) AS saved_usd,
                               coalesce(avg(latency_ms), 0) AS avg_latency_ms,
                               coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95_latency_ms,
                               coalesce(percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms), 0) AS p99_latency_ms
                          FROM usage_records
                         WHERE""" + RANGE)
                .params(range(from, to, tenantId))
                .query((rs, n) -> new Totals(rs.getLong("requests"), rs.getLong("successes"), rs.getLong("errors"),
                        rs.getLong("blocked"), rs.getLong("throttled"), rs.getLong("cache_hits"), rs.getLong("cache_lookups"),
                        rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getBigDecimal("cost_usd"),
                        rs.getBigDecimal("saved_usd"), rs.getDouble("avg_latency_ms"), rs.getDouble("p95_latency_ms"),
                        rs.getDouble("p99_latency_ms")))
                .single();

        String bucket = Duration.between(from, to).compareTo(Duration.ofDays(2)) <= 0 ? "hour" : "day";
        List<Bucket> series = jdbc.sql("SELECT date_trunc('" + bucket + "', created_at) AS bucket, count(*) AS requests,"
                        + " coalesce(sum(total_tokens), 0) AS tokens, coalesce(sum(cost_usd), 0) AS cost_usd,"
                        + " coalesce(sum(saved_usd), 0) AS saved_usd,"
                        + " count(*) FILTER (WHERE cache_status = 'HIT') AS cache_hits,"
                        + " count(*) FILTER (WHERE outcome <> 'success') AS failures"
                        + " FROM usage_records WHERE" + RANGE + " GROUP BY 1 ORDER BY 1")
                .params(range(from, to, tenantId))
                .query((rs, n) -> new Bucket(instant(rs.getTimestamp("bucket")), rs.getLong("requests"),
                        rs.getLong("tokens"), rs.getBigDecimal("cost_usd"), rs.getBigDecimal("saved_usd"),
                        rs.getLong("cache_hits"), rs.getLong("failures")))
                .list();

        return new Summary(from, to, bucket, totals, series,
                breakdown("model", from, to, tenantId),
                breakdown("provider", from, to, tenantId),
                tenantBreakdown(from, to, tenantId));
    }

    public List<RequestRow> recentRequests(UUID tenantId, String outcome, int limit) {
        return jdbc.sql("""
                        SELECT u.*, t.name AS tenant_name
                          FROM usage_records u JOIN tenants t ON t.id = u.tenant_id
                         WHERE (CAST(:tenantId AS uuid) IS NULL OR u.tenant_id = CAST(:tenantId AS uuid))
                           AND (CAST(:outcome AS text) IS NULL OR u.outcome = CAST(:outcome AS text))
                         ORDER BY u.created_at DESC
                         LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("outcome", outcome)
                .param("limit", limit)
                .query((rs, n) -> new RequestRow(rs.getLong("id"), rs.getString("request_id"),
                        rs.getObject("tenant_id", UUID.class), rs.getString("tenant_name"),
                        rs.getObject("api_key_id", UUID.class), rs.getString("model"), rs.getString("provider"),
                        rs.getString("outcome"), rs.getString("cache_status"), rs.getBoolean("streamed"),
                        rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getBigDecimal("cost_usd"),
                        rs.getBigDecimal("saved_usd"), rs.getLong("latency_ms"), rs.getString("error_code"),
                        instant(rs.getTimestamp("created_at"))))
                .list();
    }

    public List<AuditRow> auditEvents(UUID tenantId, int limit) {
        return jdbc.sql("""
                        SELECT a.*, a.details::text AS details_json, t.name AS tenant_name
                          FROM audit_log a JOIN tenants t ON t.id = a.tenant_id
                         WHERE CAST(:tenantId AS uuid) IS NULL OR a.tenant_id = CAST(:tenantId AS uuid)
                         ORDER BY a.created_at DESC
                         LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query((rs, n) -> new AuditRow(rs.getLong("id"), rs.getString("request_id"),
                        rs.getObject("tenant_id", UUID.class), rs.getString("tenant_name"),
                        rs.getObject("api_key_id", UUID.class), rs.getString("event_type"),
                        json(rs.getString("details_json")), instant(rs.getTimestamp("created_at"))))
                .list();
    }

    private List<Breakdown> breakdown(String column, Instant from, Instant to, UUID tenantId) {
        return jdbc.sql("SELECT coalesce(" + column + ", 'n/a') AS label, count(*) AS requests,"
                        + " coalesce(sum(total_tokens), 0) AS tokens, coalesce(sum(cost_usd), 0) AS cost_usd,"
                        + " coalesce(sum(saved_usd), 0) AS saved_usd,"
                        + " count(*) FILTER (WHERE cache_status = 'HIT') AS cache_hits,"
                        + " count(*) FILTER (WHERE outcome = 'error') AS errors,"
                        + " coalesce(avg(latency_ms), 0) AS avg_latency_ms"
                        + " FROM usage_records WHERE" + RANGE + " GROUP BY 1 ORDER BY requests DESC LIMIT 20")
                .params(range(from, to, tenantId))
                .query(UsageQueries::mapBreakdown)
                .list();
    }

    private List<Breakdown> tenantBreakdown(Instant from, Instant to, UUID tenantId) {
        return jdbc.sql("SELECT t.name AS label, count(*) AS requests,"
                        + " coalesce(sum(u.total_tokens), 0) AS tokens, coalesce(sum(u.cost_usd), 0) AS cost_usd,"
                        + " coalesce(sum(u.saved_usd), 0) AS saved_usd,"
                        + " count(*) FILTER (WHERE u.cache_status = 'HIT') AS cache_hits,"
                        + " count(*) FILTER (WHERE u.outcome = 'error') AS errors,"
                        + " coalesce(avg(u.latency_ms), 0) AS avg_latency_ms"
                        + " FROM usage_records u JOIN tenants t ON t.id = u.tenant_id"
                        + " WHERE u.created_at >= :from AND u.created_at < :to"
                        + " AND (CAST(:tenantId AS uuid) IS NULL OR u.tenant_id = CAST(:tenantId AS uuid))"
                        + " GROUP BY t.name ORDER BY cost_usd DESC, requests DESC LIMIT 20")
                .params(range(from, to, tenantId))
                .query(UsageQueries::mapBreakdown)
                .list();
    }

    private static Breakdown mapBreakdown(ResultSet rs, int n) throws SQLException {
        return new Breakdown(rs.getString("label"), rs.getLong("requests"), rs.getLong("tokens"),
                rs.getBigDecimal("cost_usd"), rs.getBigDecimal("saved_usd"), rs.getLong("cache_hits"),
                rs.getLong("errors"), rs.getDouble("avg_latency_ms"));
    }

    private static Map<String, Object> range(Instant from, Instant to, UUID tenantId) {
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        params.put("from", Timestamp.from(from));
        params.put("to", Timestamp.from(to));
        params.put("tenantId", tenantId);
        return params;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Totals(long requests, long successes, long errors, long blocked, long throttled, long cacheHits,
                         long cacheLookups, long promptTokens, long completionTokens, BigDecimal costUsd,
                         BigDecimal savedUsd, double avgLatencyMs, double p95LatencyMs, double p99LatencyMs) {
    }

    public record Bucket(Instant start, long requests, long tokens, BigDecimal costUsd, BigDecimal savedUsd,
                         long cacheHits, long failures) {
    }

    public record Breakdown(String label, long requests, long tokens, BigDecimal costUsd, BigDecimal savedUsd,
                            long cacheHits, long errors, double avgLatencyMs) {
    }

    public record Summary(Instant from, Instant to, String bucket, Totals totals, List<Bucket> series,
                          List<Breakdown> byModel, List<Breakdown> byProvider, List<Breakdown> byTenant) {
    }

    public record RequestRow(long id, String requestId, UUID tenantId, String tenantName, UUID apiKeyId, String model,
                             String provider, String outcome, String cacheStatus, boolean streamed, long promptTokens,
                             long completionTokens, BigDecimal costUsd, BigDecimal savedUsd, long latencyMs,
                             String errorCode, Instant createdAt) {
    }

    public record AuditRow(long id, String requestId, UUID tenantId, String tenantName, UUID apiKeyId, String eventType,
                           JsonNode details, Instant createdAt) {
    }
}

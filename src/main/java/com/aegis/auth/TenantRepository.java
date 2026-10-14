package com.aegis.auth;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.aegis.guard.InjectionMode;

/**
 * Blocking JDBC access to tenants and API keys. Callers on the event loop wrap calls in
 * {@link com.aegis.support.Blocking}.
 */
@Repository
public class TenantRepository {

    private static final String TENANT_COLUMNS = """
            t.id, t.name, t.active, t.daily_token_limit, t.monthly_token_limit, t.requests_per_minute,
            t.allowed_models, t.injection_mode, t.pii_redaction, t.cache_enabled, t.cache_similarity_threshold,
            t.created_at, t.updated_at""";

    private static final String KEY_COLUMNS = """
            k.id AS key_id, k.tenant_id AS key_tenant_id, k.name AS key_name, k.key_prefix,
            k.allowed_models AS key_allowed_models, k.daily_token_limit AS key_daily_token_limit,
            k.created_at AS key_created_at, k.revoked_at, k.last_used_at""";

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Tenant> findAll() {
        return jdbc.sql("SELECT " + TENANT_COLUMNS + " FROM tenants t ORDER BY t.name")
                .query((rs, n) -> mapTenant(rs))
                .list();
    }

    public Optional<Tenant> findById(UUID id) {
        return jdbc.sql("SELECT " + TENANT_COLUMNS + " FROM tenants t WHERE t.id = :id")
                .param("id", id)
                .query((rs, n) -> mapTenant(rs))
                .optional();
    }

    public Optional<Tenant> findByName(String name) {
        return jdbc.sql("SELECT " + TENANT_COLUMNS + " FROM tenants t WHERE t.name = :name")
                .param("name", name)
                .query((rs, n) -> mapTenant(rs))
                .optional();
    }

    public Tenant create(TenantSettings settings) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO tenants (id, name, active, daily_token_limit, monthly_token_limit, requests_per_minute,
                                             allowed_models, injection_mode, pii_redaction, cache_enabled,
                                             cache_similarity_threshold)
                        VALUES (:id, :name, :active, :daily, :monthly, :rpm, string_to_array(:models, ','),
                                :injection, :pii, :cache, :threshold)
                        """)
                .param("id", id)
                .params(settingsParams(settings))
                .update();
        return findById(id).orElseThrow();
    }

    public Optional<Tenant> update(UUID id, TenantSettings settings) {
        int updated = jdbc.sql("""
                        UPDATE tenants SET name = :name, active = :active, daily_token_limit = :daily,
                               monthly_token_limit = :monthly, requests_per_minute = :rpm,
                               allowed_models = string_to_array(:models, ','), injection_mode = :injection,
                               pii_redaction = :pii, cache_enabled = :cache, cache_similarity_threshold = :threshold,
                               updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .params(settingsParams(settings))
                .update();
        return updated == 0 ? Optional.empty() : findById(id);
    }

    public List<ApiKey> findKeys(UUID tenantId) {
        return jdbc.sql("SELECT " + KEY_COLUMNS + " FROM api_keys k WHERE k.tenant_id = :tenantId ORDER BY k.created_at")
                .param("tenantId", tenantId)
                .query((rs, n) -> mapKey(rs))
                .list();
    }

    public ApiKey createKey(UUID tenantId, String name, String keyPrefix, String keyHash,
                            List<String> allowedModels, Long dailyTokenLimit) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO api_keys (id, tenant_id, name, key_prefix, key_hash, allowed_models, daily_token_limit)
                        VALUES (:id, :tenantId, :name, :prefix, :hash, string_to_array(:models, ','), :daily)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("name", name)
                .param("prefix", keyPrefix)
                .param("hash", keyHash)
                .param("models", String.join(",", allowedModels))
                .param("daily", dailyTokenLimit)
                .update();
        return jdbc.sql("SELECT " + KEY_COLUMNS + " FROM api_keys k WHERE k.id = :id")
                .param("id", id)
                .query((rs, n) -> mapKey(rs))
                .single();
    }

    /** Returns the tenant id of the revoked key, if it existed and was active. */
    public Optional<UUID> revokeKey(UUID keyId) {
        return jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL RETURNING tenant_id")
                .param("id", keyId)
                .query(UUID.class)
                .optional();
    }

    public Optional<String> findKeyHash(UUID keyId) {
        return jdbc.sql("SELECT key_hash FROM api_keys WHERE id = :id")
                .param("id", keyId)
                .query(String.class)
                .optional();
    }

    public List<String> findKeyHashes(UUID tenantId) {
        return jdbc.sql("SELECT key_hash FROM api_keys WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .list();
    }

    /** Resolves an active key and its tenant. Revoked keys and inactive tenants resolve to empty. */
    public Optional<TenantContext> findActiveByKeyHash(String keyHash) {
        return jdbc.sql("SELECT " + TENANT_COLUMNS + ", " + KEY_COLUMNS + """
                         FROM api_keys k JOIN tenants t ON t.id = k.tenant_id
                        WHERE k.key_hash = :hash AND k.revoked_at IS NULL AND t.active
                        """)
                .param("hash", keyHash)
                .query((rs, n) -> new TenantContext(mapTenant(rs), mapKey(rs)))
                .optional();
    }

    public void markKeyUsed(UUID keyId) {
        jdbc.sql("UPDATE api_keys SET last_used_at = now() WHERE id = :id").param("id", keyId).update();
    }

    private static java.util.Map<String, Object> settingsParams(TenantSettings s) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("name", s.name());
        params.put("active", s.active());
        params.put("daily", s.dailyTokenLimit());
        params.put("monthly", s.monthlyTokenLimit());
        params.put("rpm", s.requestsPerMinute());
        params.put("models", String.join(",", s.allowedModels()));
        params.put("injection", s.injectionMode().name());
        params.put("pii", s.piiRedaction());
        params.put("cache", s.cacheEnabled());
        params.put("threshold", s.cacheSimilarityThreshold());
        return params;
    }

    private static Tenant mapTenant(ResultSet rs) throws SQLException {
        return new Tenant(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getBoolean("active"),
                rs.getObject("daily_token_limit", Long.class),
                rs.getObject("monthly_token_limit", Long.class),
                rs.getObject("requests_per_minute", Integer.class),
                strings(rs.getArray("allowed_models")),
                InjectionMode.valueOf(rs.getString("injection_mode")),
                rs.getBoolean("pii_redaction"),
                rs.getBoolean("cache_enabled"),
                rs.getObject("cache_similarity_threshold", Double.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private static ApiKey mapKey(ResultSet rs) throws SQLException {
        return new ApiKey(
                rs.getObject("key_id", UUID.class),
                rs.getObject("key_tenant_id", UUID.class),
                rs.getString("key_name"),
                rs.getString("key_prefix"),
                strings(rs.getArray("key_allowed_models")),
                rs.getObject("key_daily_token_limit", Long.class),
                instant(rs.getTimestamp("key_created_at")),
                instant(rs.getTimestamp("revoked_at")),
                instant(rs.getTimestamp("last_used_at")));
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((String[]) array.getArray()).filter(s -> !s.isBlank()).toList();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

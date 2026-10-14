package com.aegis.cache;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatMessage;
import com.aegis.auth.TenantContext;
import com.aegis.config.AegisProperties;
import com.aegis.support.Blocking;
import com.aegis.support.Hashing;
import com.aegis.support.MessageText;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import reactor.core.publisher.Mono;

/**
 * Semantic response cache on pgvector. Entries are scoped by tenant, model and a hash of the
 * system prompt and generation parameters; within a scope, the closest prompt by cosine
 * similarity is a hit if it clears the tenant's threshold.
 *
 * <p>Callers pass already-redacted requests, so neither prompts nor stored responses hold raw PII.
 */
@Service
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    /** Parameters that do not change the answer and so do not split the cache. */
    private static final Set<String> NON_SEMANTIC_PARAMS = Set.of("stream", "stream_options", "user", "metadata", "store");
    private static final Set<String> SAFE_MESSAGE_FIELDS = Set.of("name");

    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;
    private final ObjectMapper objectMapper;
    /** Sorts map keys (nested ones too) so equal parameters always hash the same. */
    private final ObjectMapper canonicalMapper;
    private final AegisProperties.Cache config;

    public SemanticCache(JdbcClient jdbc, EmbeddingClient embeddings, ObjectMapper objectMapper, AegisProperties properties) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.objectMapper = objectMapper;
        this.canonicalMapper = objectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.config = properties.cache();
    }

    public boolean enabledFor(TenantContext ctx) {
        return config.enabled() && ctx.tenant().cacheEnabled();
    }

    /**
     * Only plain text conversations are cached. Images, tool calls and other structured content
     * are not visible to the embedding, so two different requests could look identical.
     */
    public boolean isCacheable(ChatCompletionRequest request) {
        for (ChatMessage message : request.getMessages()) {
            if (!SAFE_MESSAGE_FIELDS.containsAll(message.getAdditionalProperties().keySet())) {
                return false;
            }
            JsonNode content = message.getContent();
            if (content == null || content.isNull()) {
                return false;
            }
            if (content.isArray()) {
                for (JsonNode part : content) {
                    if (!"text".equals(part.path("type").asText())) {
                        return false;
                    }
                }
            } else if (!content.isTextual()) {
                return false;
            }
        }
        return true;
    }

    public Mono<Lookup> lookup(TenantContext ctx, ChatCompletionRequest request) {
        Key key = key(request);
        double threshold = ctx.tenant().cacheSimilarityThreshold() != null
                ? ctx.tenant().cacheSimilarityThreshold()
                : config.similarityThreshold();
        return embeddings.embed(key.promptText())
                .flatMap(embedding -> Blocking.mono(() -> findNearest(ctx.tenant().id(), key, embedding))
                        .map(nearest -> new Lookup(key, embedding, nearest.filter(hit -> hit.similarity() >= threshold))));
    }

    public Mono<Void> store(TenantContext ctx, Lookup lookup, JsonNode redactedResponse) {
        return Blocking.run(() -> jdbc.sql("""
                        INSERT INTO semantic_cache (tenant_id, model, scope_hash, prompt_text, embedding, response, expires_at)
                        VALUES (:tenantId, :model, :scope, :prompt, CAST(:embedding AS vector), CAST(:response AS jsonb), :expires)
                        """)
                .param("tenantId", ctx.tenant().id())
                .param("model", lookup.key().model())
                .param("scope", lookup.key().scopeHash())
                .param("prompt", lookup.key().promptText())
                .param("embedding", vectorLiteral(lookup.embedding()))
                .param("response", redactedResponse.toString())
                .param("expires", Timestamp.from(Instant.now().plus(config.ttl())))
                .update());
    }

    public Mono<Integer> invalidate(UUID tenantId) {
        return Blocking.mono(() -> jdbc.sql("DELETE FROM semantic_cache WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .update());
    }

    public Mono<Long> countEntries(UUID tenantId) {
        return Blocking.mono(() -> jdbc.sql("SELECT count(*) FROM semantic_cache WHERE tenant_id = :tenantId AND expires_at > now()")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single());
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void purgeExpired() {
        int removed = jdbc.sql("DELETE FROM semantic_cache WHERE expires_at <= now()").update();
        if (removed > 0) {
            log.info("Purged {} expired cache entries", removed);
        }
    }

    Key key(ChatCompletionRequest request) {
        StringBuilder system = new StringBuilder();
        StringBuilder conversation = new StringBuilder();
        for (ChatMessage message : request.getMessages()) {
            String text = MessageText.of(message);
            if ("system".equals(message.getRole()) || "developer".equals(message.getRole())) {
                system.append(text).append('\n');
            } else {
                conversation.append(message.getRole()).append(": ").append(normalize(text)).append('\n');
            }
        }
        Map<String, Object> params = new TreeMap<>(request.getAdditionalProperties());
        params.keySet().removeAll(NON_SEMANTIC_PARAMS);
        String scope = Hashing.sha256Hex(request.getModel() + "\n" + system + "\n" + canonicalJson(params));
        return new Key(request.getModel(), scope, conversation.toString().trim());
    }

    private Optional<Hit> findNearest(UUID tenantId, Key key, float[] embedding) {
        Optional<Hit> hit = jdbc.sql("""
                        SELECT id, response::text AS response, 1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
                          FROM semantic_cache
                         WHERE tenant_id = :tenantId AND model = :model AND scope_hash = :scope AND expires_at > now()
                         ORDER BY embedding <=> CAST(:embedding AS vector)
                         LIMIT 1
                        """)
                .param("embedding", vectorLiteral(embedding))
                .param("tenantId", tenantId)
                .param("model", key.model())
                .param("scope", key.scopeHash())
                .query((rs, n) -> new Hit(rs.getLong("id"), readJson(rs.getString("response")), rs.getDouble("similarity")))
                .optional();
        hit.ifPresent(h -> jdbc.sql("UPDATE semantic_cache SET hit_count = hit_count + 1 WHERE id = :id")
                .param("id", h.id()).update());
        return hit;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String canonicalJson(Map<String, Object> params) {
        try {
            return canonicalMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt cache entry", e);
        }
    }

    static String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    public record Key(String model, String scopeHash, String promptText) {
    }

    public record Hit(long id, JsonNode response, double similarity) {
    }

    public record Lookup(Key key, float[] embedding, Optional<Hit> hit) {
    }
}

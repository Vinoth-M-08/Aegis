package com.aegis.usage;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.aegis.support.Blocking;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Security events (blocked or flagged prompts, redactions). Details never include the
 * offending text or PII values, only what was detected.
 */
@Component
public class AuditLog {

    public static final String INJECTION_BLOCKED = "INJECTION_BLOCKED";
    public static final String INJECTION_FLAGGED = "INJECTION_FLAGGED";
    public static final String PII_REDACTED = "PII_REDACTED";

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public AuditLog(JdbcClient jdbc, ObjectMapper objectMapper, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    public void record(String requestId, UUID tenantId, UUID apiKeyId, String eventType, Map<String, ?> details) {
        meters.counter("aegis.security.events", "type", eventType).increment();
        Blocking.run(() -> jdbc.sql("""
                                INSERT INTO audit_log (request_id, tenant_id, api_key_id, event_type, details)
                                VALUES (:requestId, :tenantId, :apiKeyId, :type, CAST(:details AS jsonb))
                                """)
                        .param("requestId", requestId)
                        .param("tenantId", tenantId)
                        .param("apiKeyId", apiKeyId)
                        .param("type", eventType)
                        .param("details", json(details))
                        .update())
                .doOnError(e -> log.error("Failed to write audit event {} for {}", eventType, requestId, e))
                .onErrorComplete()
                .subscribe();
    }

    private String json(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

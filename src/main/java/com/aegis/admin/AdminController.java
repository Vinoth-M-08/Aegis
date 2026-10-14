package com.aegis.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aegis.admin.AdminDtos.CreateKeyRequest;
import com.aegis.admin.AdminDtos.CreatedKey;
import com.aegis.admin.AdminDtos.Deleted;
import com.aegis.admin.AdminDtos.TenantDetail;
import com.aegis.admin.AdminDtos.TenantRequest;
import com.aegis.api.GatewayException;
import com.aegis.auth.ApiKey;
import com.aegis.auth.ApiKeyAuthenticator;
import com.aegis.auth.Tenant;
import com.aegis.auth.TenantRepository;
import com.aegis.budget.BudgetService;
import com.aegis.cache.SemanticCache;
import com.aegis.config.AegisProperties;
import com.aegis.provider.ProviderRouter;
import com.aegis.support.Blocking;
import com.aegis.usage.UsageQueries;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final int MAX_ROWS = 500;

    private final TenantRepository tenants;
    private final ApiKeyAuthenticator authenticator;
    private final BudgetService budgets;
    private final SemanticCache cache;
    private final UsageQueries usage;
    private final ProviderRouter router;
    private final AegisProperties properties;

    public AdminController(TenantRepository tenants, ApiKeyAuthenticator authenticator, BudgetService budgets,
                           SemanticCache cache, UsageQueries usage, ProviderRouter router, AegisProperties properties) {
        this.tenants = tenants;
        this.authenticator = authenticator;
        this.budgets = budgets;
        this.cache = cache;
        this.usage = usage;
        this.router = router;
        this.properties = properties;
    }

    // ---- tenants ------------------------------------------------------------------------------

    @GetMapping("/tenants")
    public Mono<List<Tenant>> listTenants() {
        return Blocking.mono(tenants::findAll);
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Tenant> createTenant(@Valid @RequestBody TenantRequest request) {
        return Blocking.mono(() -> tenants.create(request.toSettings()));
    }

    @GetMapping("/tenants/{id}")
    public Mono<TenantDetail> getTenant(@PathVariable UUID id) {
        return findTenant(id).flatMap(tenant -> Mono.zip(
                Blocking.mono(() -> tenants.findKeys(id)),
                budgets.usage(tenant),
                cache.countEntries(id))
                .map(t -> new TenantDetail(tenant, t.getT1(), t.getT2(), t.getT3())));
    }

    @PutMapping("/tenants/{id}")
    public Mono<Tenant> updateTenant(@PathVariable UUID id, @Valid @RequestBody TenantRequest request) {
        return Blocking.mono(() -> {
            Tenant updated = tenants.update(id, request.toSettings()).orElseThrow(() -> notFound("Tenant", id));
            evictKeys(id);
            return updated;
        });
    }

    // ---- API keys -----------------------------------------------------------------------------

    @GetMapping("/tenants/{id}/keys")
    public Mono<List<ApiKey>> listKeys(@PathVariable UUID id) {
        return findTenant(id).then(Blocking.mono(() -> tenants.findKeys(id)));
    }

    @PostMapping("/tenants/{id}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CreatedKey> createKey(@PathVariable UUID id, @Valid @RequestBody CreateKeyRequest request) {
        return findTenant(id).then(Blocking.mono(() -> {
            ApiKeyAuthenticator.GeneratedKey key = authenticator.generateKey();
            ApiKey stored = tenants.createKey(id, request.name().trim(), key.displayPrefix(), key.hash(),
                    request.models(), request.dailyTokenLimit());
            return new CreatedKey(stored, key.plaintext());
        }));
    }

    @DeleteMapping("/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revokeKey(@PathVariable UUID keyId) {
        return Blocking.run(() -> {
            tenants.revokeKey(keyId).orElseThrow(() -> notFound("Active API key", keyId));
            tenants.findKeyHash(keyId).ifPresent(authenticator::evict);
        });
    }

    // ---- cache --------------------------------------------------------------------------------

    @DeleteMapping("/tenants/{id}/cache")
    public Mono<Deleted> invalidateCache(@PathVariable UUID id) {
        return findTenant(id).then(cache.invalidate(id)).map(Deleted::new);
    }

    // ---- usage and audit ----------------------------------------------------------------------

    /**
     * @param hours look-back window, ignored when {@code from} is given
     */
    @GetMapping("/usage/summary")
    public Mono<UsageQueries.Summary> usageSummary(@RequestParam(required = false) Instant from,
                                                   @RequestParam(required = false) Instant to,
                                                   @RequestParam(defaultValue = "24") int hours,
                                                   @RequestParam(required = false) UUID tenantId) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofHours(Math.max(1, Math.min(hours, 24 * 90))));
        if (!start.isBefore(end)) {
            throw GatewayException.invalidRequest("invalid_range", "'from' must be before 'to'.");
        }
        return Blocking.mono(() -> usage.summary(start, end, tenantId));
    }

    @GetMapping("/usage/requests")
    public Mono<List<UsageQueries.RequestRow>> recentRequests(@RequestParam(required = false) UUID tenantId,
                                                              @RequestParam(required = false) String outcome,
                                                              @RequestParam(defaultValue = "100") int limit) {
        return Blocking.mono(() -> usage.recentRequests(tenantId, blankToNull(outcome), clamp(limit)));
    }

    @GetMapping("/audit")
    public Mono<List<UsageQueries.AuditRow>> auditLog(@RequestParam(required = false) UUID tenantId,
                                                      @RequestParam(defaultValue = "100") int limit) {
        return Blocking.mono(() -> usage.auditEvents(tenantId, clamp(limit)));
    }

    // ---- gateway state ------------------------------------------------------------------------

    @GetMapping("/providers")
    public List<ProviderRouter.ProviderHealth> providers() {
        return router.health();
    }

    /** Effective gateway defaults, so the dashboard can explain what "default" means. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("budgets", properties.budgets());
        config.put("cache", Map.of(
                "enabled", properties.cache().enabled(),
                "similarityThreshold", properties.cache().similarityThreshold(),
                "ttl", properties.cache().ttl().toString(),
                "embedding", properties.cache().embedding().type().name().toLowerCase()));
        config.put("security", properties.security());
        config.put("pricing", properties.pricing());
        return config;
    }

    private Mono<Tenant> findTenant(UUID id) {
        return Blocking.mono(() -> tenants.findById(id).orElseThrow(() -> notFound("Tenant", id)));
    }

    private void evictKeys(UUID tenantId) {
        tenants.findKeyHashes(tenantId).forEach(authenticator::evict);
    }

    private static GatewayException notFound(String what, UUID id) {
        return new GatewayException(HttpStatus.NOT_FOUND, "not_found_error", "not_found", what + " " + id + " not found.");
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_ROWS));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

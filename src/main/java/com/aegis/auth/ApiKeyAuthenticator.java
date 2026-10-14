package com.aegis.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.aegis.api.GatewayException;
import com.aegis.support.Blocking;
import com.aegis.support.Hashing;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import reactor.core.publisher.Mono;

/**
 * Resolves bearer API keys to tenants. Lookups are cached briefly so the database is not hit on
 * every request; admin changes evict the affected entries immediately.
 */
@Component
public class ApiKeyAuthenticator {

    public static final String KEY_PREFIX = "aegis_";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int KEY_RANDOM_LENGTH = 40;
    private static final int DISPLAY_PREFIX_LENGTH = 12;

    private final TenantRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, Optional<TenantContext>> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public ApiKeyAuthenticator(TenantRepository repository) {
        this.repository = repository;
    }

    public Mono<TenantContext> authenticate(String authorizationHeader) {
        String key = bearerToken(authorizationHeader);
        if (key == null) {
            return Mono.error(GatewayException.unauthorized(
                    "Missing API key. Send it as 'Authorization: Bearer <key>'."));
        }
        String hash = Hashing.sha256Hex(key);
        Optional<TenantContext> cached = cache.getIfPresent(hash);
        Mono<Optional<TenantContext>> lookup = cached != null
                ? Mono.just(cached)
                : Blocking.mono(() -> {
                    Optional<TenantContext> found = repository.findActiveByKeyHash(hash);
                    found.ifPresent(ctx -> repository.markKeyUsed(ctx.apiKey().id()));
                    cache.put(hash, found);
                    return found;
                });
        return lookup.flatMap(found -> found
                .map(Mono::just)
                .orElseGet(() -> Mono.error(GatewayException.unauthorized("Invalid or revoked API key."))));
    }

    /** Generates a new random key. Only its hash is stored. */
    public GeneratedKey generateKey() {
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        for (int i = 0; i < KEY_RANDOM_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return GeneratedKey.of(sb.toString());
    }

    public void evict(String keyHash) {
        cache.invalidate(keyHash);
    }

    private static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    public record GeneratedKey(String plaintext, String hash, String displayPrefix) {

        public static GeneratedKey of(String plaintext) {
            return new GeneratedKey(plaintext, Hashing.sha256Hex(plaintext),
                    plaintext.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, plaintext.length())));
        }
    }
}

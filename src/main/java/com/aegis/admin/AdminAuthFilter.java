package com.aegis.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.aegis.config.AegisProperties;

import reactor.core.publisher.Mono;

/**
 * Protects /admin/** with a single bearer token ({@code aegis.admin.token}). Without a token
 * configured the admin API is switched off entirely.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminAuthFilter implements WebFilter {

    private final AegisProperties.Admin config;

    public AdminAuthFilter(AegisProperties properties) {
        this.config = properties.admin();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.startsWith("/admin/") || exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }
        if (!config.enabled()) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "The admin API is disabled. Set aegis.admin.token (AEGIS_ADMIN_TOKEN) to enable it.");
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String expected = "Bearer " + config.token();
        if (header == null || !MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid admin token.");
        }
        return chain.filter(exchange);
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":{\"message\":\"" + message.replace("\"", "\\\"")
                + "\",\"type\":\"authentication_error\"}}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}

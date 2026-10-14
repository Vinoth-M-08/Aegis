package com.aegis.api;

import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Tags every response, including errors, with a request id that later pipeline stages and
 * the usage ledger can refer to.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(AegisHeaders.REQUEST_ID, requestId);
        return chain.filter(exchange);
    }
}

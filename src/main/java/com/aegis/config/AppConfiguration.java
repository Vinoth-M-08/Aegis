package com.aegis.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.aegis.cache.EmbeddingClient;
import com.aegis.cache.HashingEmbeddingClient;
import com.aegis.cache.OpenAiEmbeddingClient;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AegisProperties.class)
public class AppConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EmbeddingClient embeddingClient(AegisProperties properties, WebClient.Builder webClientBuilder) {
        AegisProperties.Embedding embedding = properties.cache().embedding();
        if (!properties.cache().enabled() || embedding.type() == AegisProperties.EmbeddingType.HASHING) {
            return new HashingEmbeddingClient();
        }
        return new OpenAiEmbeddingClient(embedding, webClientBuilder);
    }

    /** Lets the dashboard dev server (or another origin) call the admin API. */
    @Bean
    CorsWebFilter adminCorsFilter(AegisProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        properties.admin().corsOrigins().forEach(cors::addAllowedOrigin);
        cors.addAllowedMethod("*");
        cors.addAllowedHeader("*");
        cors.addExposedHeader("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!properties.admin().corsOrigins().isEmpty()) {
            source.registerCorsConfiguration("/admin/**", cors);
        }
        return new CorsWebFilter(source);
    }

    /**
     * Serves the built dashboard's entry page. Static assets under /dashboard/** are served by
     * Spring's default resource handling; the dashboard uses hash routing so no other fallback is needed.
     */
    @Bean
    RouterFunction<ServerResponse> dashboardRoutes() {
        ClassPathResource index = new ClassPathResource("static/dashboard/index.html");
        return route(GET("/dashboard").or(GET("/dashboard/")), request -> index.exists()
                ? ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(index)
                : ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).bodyValue(
                        "The dashboard is not built into this jar. Run it with 'npm run dev' in dashboard/, "
                                + "or build the Docker image."));
    }
}

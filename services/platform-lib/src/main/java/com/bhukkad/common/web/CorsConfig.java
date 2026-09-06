package com.bhukkad.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS configuration for services.
 *
 * <p>Origins are configured per environment through {@code app.cors.allowed-origins}
 * (comma-separated). Credentials mode only activates when an explicit origin
 * list is configured — wildcard origins combined with
 * {@code allowCredentials=true} would let any site make credentialed calls,
 * which is a credential-leak vector and is rejected at startup.</p>
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter(@Value("${app.cors.allowed-origins:}") String originsProperty) {
        List<String> origins = Arrays.stream(originsProperty.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        if (origins.isEmpty()) {
            // No configured origins: allow everything, but NEVER with
            // credentials (matches a dev posture without leaking cookies).
            config.addAllowedOriginPattern("*");
            config.setAllowCredentials(false);
        } else {
            config.setAllowedOrigins(origins);
            config.setAllowCredentials(true);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With",
                "Idempotency-Key", "X-Request-Id"));
        config.setExposedHeaders(List.of("Trace-Id", "ETag", "Retry-After"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}

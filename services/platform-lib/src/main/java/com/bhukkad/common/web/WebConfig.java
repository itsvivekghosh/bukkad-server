package com.bhukkad.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Platform web configuration: CORS and a page-size interceptor that rejects
 * oversized pages (HTTP 400) instead of silently clamping them (audit P1-01 /
 * SV-11 / SV-15).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Value("${app.pagination.max-page-size:100}")
    private int maxPageSize;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.isBlank() ? new String[0] : allowedOrigins.split(",");
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id", "X-Request-Id", "X-Timestamp", "X-Correlation-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * Registers the page-size interceptor so any `?size=N` above
     * {@code app.pagination.max-page-size} (default 100) is rejected with a
     * 400, preventing unbounded result-set materialization under fan-out
     * traffic.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PageSizeInterceptor(maxPageSize))
                .addPathPatterns("/api/**");
    }
}

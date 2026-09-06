package com.bhukkad.common.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link ServiceJwtAuthFilter} bean whenever a service-JWT
 * secret is configured.
 *
 * <p>Before this configuration existed no bean was ever created, so every
 * service's {@code SecurityConfig} found the filter absent and internal
 * endpoints ({@code /api/v1/internal/**}) were guarded by nothing more than
 * "any authenticated principal" — i.e. any ordinary customer JWT.</p>
 */
@Configuration
@EnableConfigurationProperties(ServiceAuthProperties.class)
@ConditionalOnProperty(name = "app.auth.service.jwt-secret")
public class ServiceAuthAutoConfiguration {

    @Bean
    public ServiceJwtAuthFilter serviceJwtAuthFilter(ServiceAuthProperties properties) {
        return new ServiceJwtAuthFilter(properties);
    }
}

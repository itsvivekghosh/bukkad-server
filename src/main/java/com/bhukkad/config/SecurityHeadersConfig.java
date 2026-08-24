package com.bhukkad.config;

import com.bhukkad.security.SecurityHeadersProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link SecurityHeadersProperties} as an application-level
 * {@code @ConfigurationProperties} bean, matching the existing
 * {@code @EnableConfigurationProperties} convention used across the codebase.
 */
@Configuration
@EnableConfigurationProperties(SecurityHeadersProperties.class)
public class SecurityHeadersConfig {
}

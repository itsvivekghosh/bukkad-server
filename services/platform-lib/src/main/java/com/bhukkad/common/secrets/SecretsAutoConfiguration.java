package com.bhukkad.common.secrets;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Auto-configuration for the file-based Secrets Manager integration
 * (architecture audit §2.3).
 *
 * <p>Registered through {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so it applies to every service on the platform-lib classpath — including the
 * reactive gateway, which does not component-scan {@code com.bhukkad.common}.</p>
 *
 * <p><b>Dependency note:</b> {@code io.awspring.cloud:spring-cloud-aws-secretsmanager-config:3.4.0}
 * was considered for native AWS Secrets Manager config binding but does not resolve
 * from the configured Maven repositories, so it is deliberately not added. This module
 * implements the file-based CSI-driver approach alone (architecture audit §2.3, file
 * mounting) instead of the Spring Cloud AWS config integration.</p>
 */
@AutoConfiguration
public class SecretsAutoConfiguration {

    /**
     * The {@link SecretResolver} reads mounted secret files fresh from disk on
     * every call, so a rotated secret is picked up without caching.
     */
    @Bean
    @ConditionalOnMissingBean
    public SecretResolver secretResolver(ConfigurableEnvironment environment) {
        return new SecretResolver(environment);
    }

    /**
     * Periodically re-reads the mounted secret files and pushes changed values
     * into the environment and the bound {@code @ConfigurationProperties} beans,
     * so property-bound consumers (e.g. JWT validation, service-to-service
     * authentication) see rotated secrets without a pod restart.
     *
     * <p>Disabled by default — enable in production via
     * {@code app.secrets.rotation.enabled=true} (already set in each service's
     * prod profile).</p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "app.secrets.rotation", name = "enabled", havingValue = "true")
    public SecretRotationRefresher secretRotationRefresher(ConfigurableEnvironment environment,
                                                           ApplicationContext applicationContext) {
        Duration interval = environment.getProperty("app.secrets.rotation.interval", Duration.class, Duration.ofSeconds(60));
        return new SecretRotationRefresher(environment, applicationContext, interval);
    }
}

package com.bhukkad.common.config;

import com.bhukkad.common.cache.LocalCacheProperties;
import com.bhukkad.common.cache.StampedeProperties;
import com.bhukkad.common.chaos.ChaosProperties;
import com.bhukkad.common.cluster.ClusterProperties;
import com.bhukkad.common.datasource.ReadReplicaProperties;
import com.bhukkad.common.kafka.KafkaPlatformProperties;
import com.bhukkad.common.metrics.SloProperties;
import com.bhukkad.common.outbox.OutboxProperties;
import com.bhukkad.common.ratelimit.RateLimitProperties;
import com.bhukkad.common.security.PlatformJwtProperties;
import com.bhukkad.common.security.ServiceAuthProperties;
import com.bhukkad.common.web.VersionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers every platform-lib {@code @ConfigurationProperties} bean so that
 * downstream services (and the monolith, until P8 teardown) get them
 * auto-wired without having to list each one in their own
 * {@code @EnableConfigurationProperties}.
 *
 * <p>Each property class is also individually annotated with
 * {@code @ConfigurationProperties(prefix = ...)}; this class is purely
 * the registration list. Services that want to opt out of a particular
 * platform properties class (e.g. the reactive gateway, which does not
 * use outbox) should annotate their main class with
 * {@code @EnableConfigurationProperties} explicitly and rely on
 * property class scanning to ignore the unwanted ones.</p>
 */
@Configuration
@EnableConfigurationProperties({
        ChaosProperties.class,
        ClusterProperties.class,
        KafkaPlatformProperties.class,
        LocalCacheProperties.class,
        OutboxProperties.class,
        PlatformJwtProperties.class,
        RateLimitProperties.class,
        ReadReplicaProperties.class,
        ServiceAuthProperties.class,
        SloProperties.class,
        StampedeProperties.class,
        VersionProperties.class
})
public class PlatformEnhancementsConfig {
}

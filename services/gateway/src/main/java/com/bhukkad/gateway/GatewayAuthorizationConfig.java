package com.bhukkad.gateway;

import com.bhukkad.common.security.authz.AuthorizationPolicy;
import com.bhukkad.common.security.authz.AuthorizationService;
import com.bhukkad.common.security.PlatformJwtProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Gateway wiring for the local authorization engine.
 *
 * <p>The gateway does not own domain policies. It creates the same
 * platform-lib {@link AuthorizationService} used by services so the edge can
 * be enabled incrementally once edge-appropriate policies are registered.</p>
 */
@Configuration
@EnableConfigurationProperties(PlatformJwtProperties.class)
public class GatewayAuthorizationConfig {

    @Bean
    public AuthorizationService gatewayAuthorizationService(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<AuthorizationPolicy> policyProvider) {
        List<AuthorizationPolicy> policies = policyProvider.orderedStream().toList();
        return new AuthorizationService(policies,
                meterRegistryProvider.getIfAvailable(),
                null);
    }
}

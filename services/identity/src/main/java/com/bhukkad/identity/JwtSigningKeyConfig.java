package com.bhukkad.identity;

import com.bhukkad.identity.security.RsaKeyProperties;
import com.bhukkad.identity.security.RsaSigningKeys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * RS256 signing-key wiring (ADR-004 step 1). The prod/staging strictness flag
 * is resolved here (Environment access) so the security package itself stays
 * Spring-lean per the identity ArchUnit rules.
 */
@Configuration
@EnableConfigurationProperties(RsaKeyProperties.class)
public class JwtSigningKeyConfig {

    @Bean
    public RsaSigningKeys rsaSigningKeys(RsaKeyProperties properties, Environment environment) {
        boolean strictProfile = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "staging".equalsIgnoreCase(profile)) {
                strictProfile = true;
                break;
            }
        }
        return new RsaSigningKeys(properties, strictProfile);
    }
}

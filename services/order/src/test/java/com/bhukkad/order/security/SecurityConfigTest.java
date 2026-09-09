package com.bhukkad.order.security;

import com.bhukkad.common.security.PlatformJwtAuthFilter;
import com.bhukkad.common.security.PlatformJwtProperties;
import com.bhukkad.order.AbstractOrderPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the security wiring: with {@code app.auth.jwt.secret} set the
 * {@link PlatformJwtAuthFilter} is registered and the REST security chain is
 * active (all order endpoints require a token).
 */
@SpringBootTest(properties = "app.auth.jwt.secret=0123456789abcdef0123456789abcdef")
class SecurityConfigTest extends AbstractOrderPostgresTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void jwtAuthFilterIsRegistered() {
        assertThat(applicationContext.getBean(PlatformJwtAuthFilter.class)).isNotNull();
        assertThat(applicationContext.getBean(PlatformJwtProperties.class)).isNotNull();
    }

    @Test
    void securityFilterChainIsActive() {
        assertThat(applicationContext.getBean(SecurityFilterChain.class)).isNotNull();
    }
}

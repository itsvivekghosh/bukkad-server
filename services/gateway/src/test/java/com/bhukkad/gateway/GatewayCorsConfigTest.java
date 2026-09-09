package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.cors.reactive.CorsWebFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit V-14: credentialed CORS must never ride a wildcard at the edge —
 * prod/staging fail the boot on wildcard origins (explicit or the unset-list
 * fallback), while dev/local keeps the permissive wildcard-without-credentials
 * posture when nothing is configured.
 */
class GatewayCorsConfigTest {

    private final GatewayCorsConfig config = new GatewayCorsConfig();

    @Test
    void devWithoutOrigins_keepsCurrentPermissiveDevBehavior() {
        MockEnvironment env = new MockEnvironment();

        assertThatCode(() -> config.corsWebFilter(env)).doesNotThrowAnyException();
        CorsWebFilter filter = config.corsWebFilter(env);
        assertThat(filter).isNotNull();
    }

    @Test
    void devWithLocalhostPatternOrigins_isAllowed() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.cors.allowed-origins",
                        "http://localhost:[*],capacitor://localhost");

        assertThatCode(() -> config.corsWebFilter(env)).doesNotThrowAnyException();
    }

    @Test
    void prodWithExplicitOrigins_boots() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.cors.allowed-origins", "https://app.bhukkad.example");
        env.setActiveProfiles("prod");

        assertThatCode(() -> config.corsWebFilter(env)).doesNotThrowAnyException();
    }

    @Test
    void prodWithWildcardOrigin_failsBoot() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.cors.allowed-origins", "https://*.example");
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> config.corsWebFilter(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V-14");
    }

    @Test
    void prodWithNoOriginsConfigured_failsBoot_becauseFallbackWouldBeWildcard() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> config.corsWebFilter(env))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stagingWithWildcard_failsBootTo() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.cors.allowed-origins", "*");
        env.setActiveProfiles("staging");

        assertThatThrownBy(() -> config.corsWebFilter(env))
                .isInstanceOf(IllegalStateException.class);
    }
}

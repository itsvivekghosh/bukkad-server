package com.bhukkad.common.web.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1 boot self-check (audit PERF-1/V-16, docs §VI.4.2 step 4): with a
 * {@link CircuitBreakerRegistry} bean present, every configured outbound
 * target must have a mounted breaker — WebClient-mounted (shared registry)
 * or annotation/auto-configured (bean registry); WITHOUT the bean the check
 * is a no-op so contexts never wired for breakers cannot fail on it.
 */
class CircuitBreakerPreflightTest {

    private static ObjectProvider<CircuitBreakerRegistry> providerOf(CircuitBreakerRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public CircuitBreakerRegistry getObject() {
                return registry;
            }

            @Override
            public CircuitBreakerRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public CircuitBreakerRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public CircuitBreakerRegistry getIfUnique() {
                return registry;
            }
        };
    }

    private static CircuitBreakerRegistry stubRegistry(String... mounted) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        for (String name : mounted) {
            registry.circuitBreaker(name);
        }
        return registry;
    }

    private static CircuitBreakerPreflight preflight(CircuitBreakerRegistry beanRegistry,
                                                     String... targets) {
        return new CircuitBreakerPreflight(
                providerOf(beanRegistry),
                new CircuitBreakerPreflightProperties(List.of(targets)));
    }

    @Test
    void noRegistryBean_isANoOp_evenWithMissingTargets() {
        // Context-boot safety: contexts never wired breaker infra must pass.
        assertThatCode(() -> preflight(null, "identity", "restaurant").verifyMountedBreakers())
                .doesNotThrowAnyException();
    }

    @Test
    void registryBeanWithoutTargets_justLogsAndPasses() {
        assertThatCode(() -> preflight(stubRegistry()).verifyMountedBreakers())
                .doesNotThrowAnyException();
    }

    @Test
    void allConfiguredTargetsMounted_passes() {
        CircuitBreakerRegistry registry = stubRegistry("identity", "restaurant");

        assertThatCode(() -> preflight(registry, "restaurant", "identity").verifyMountedBreakers())
                .doesNotThrowAnyException();
    }

    @Test
    void configuredTargetWithoutCoverage_failsFastNamingTheGap() {
        CircuitBreakerRegistry registry = stubRegistry("identity");

        assertThatThrownBy(() -> preflight(registry, "identity", "payment").verifyMountedBreakers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment")
                .hasMessageContaining("outbound-targets");
    }

    @Test
    void breakerMountedViaWebClientSharedRegistry_countsAsCoverage() {
        // Factory/filter construction mounts into the JVM-static shared
        // registry, not the Spring bean — the self-check spans both.
        String target = "preflight-" + System.nanoTime();
        new CircuitBreakerFilter(target, CircuitBreakerFilter.DEFAULT_CONFIG);

        assertThatCode(() -> preflight(stubRegistry(), target).verifyMountedBreakers())
                .doesNotThrowAnyException();
    }

    // ─── context-boot safety (the preflight is a boot listener) ────────────────

    private static final ApplicationContextInitializer<ConfigurableApplicationContext> PROD =
            context -> context.getEnvironment().setActiveProfiles("prod");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(PROD)
            .withUserConfiguration(CircuitBreakerPreflightConfiguration.class);

    @Test
    void prodContextWithoutRegistryBean_bootsWithThePreflightArmedButInert() {
        runner.withPropertyValues("platform.web.outbound-targets[0]=identity")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CircuitBreakerPreflight.class);
                    // No CircuitBreakerRegistry bean in this context: replay of
                    // the ready event must be a silent no-op, not a failure.
                    assertThatCode(() -> context.getBean(CircuitBreakerPreflight.class)
                            .verifyMountedBreakers()).doesNotThrowAnyException();
                });
    }

    @Test
    void prodContextWithRegistryBeanAndGap_failsFast() {
        runner.withBean(CircuitBreakerRegistry.class, () -> stubRegistry("identity"))
                .withPropertyValues("platform.web.outbound-targets[0]=search")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatThrownBy(() -> context.getBean(CircuitBreakerPreflight.class)
                            .verifyMountedBreakers())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("search");
                });
    }

    @Test
    void nonProdContext_registersNoPreflightBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(CircuitBreakerPreflightConfiguration.class)
                .withBean(CircuitBreakerRegistry.class, CircuitBreakerPreflightTest::stubEmpty)
                .run(context -> assertThat(context).doesNotHaveBean(CircuitBreakerPreflight.class));
    }

    private static CircuitBreakerRegistry stubEmpty() {
        return stubRegistry();
    }
}

package com.bhukkad.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G-3 style startup preflight (audit V-18): in prod, a registered
 * auth-subject-or-client-IP key resolver is MANDATORY — a service that boots
 * without one would silently collapse every caller into the aspect's coarse
 * fallback identifier.
 */
class RateLimitStartupGuardTest {

    private static RateLimitStartupGuard guardWith(RateLimitAspect.RateLimitKeyResolver resolver) {
        ObjectProvider<RateLimitAspect.RateLimitKeyResolver> provider =
                new ObjectProvider<>() {
                    @Override
                    public RateLimitAspect.RateLimitKeyResolver getObject() {
                        return resolver;
                    }

                    @Override
                    public RateLimitAspect.RateLimitKeyResolver getObject(Object... args) {
                        return resolver;
                    }

                    @Override
                    public RateLimitAspect.RateLimitKeyResolver getIfAvailable() {
                        return resolver;
                    }

                    @Override
                    public RateLimitAspect.RateLimitKeyResolver getIfUnique() {
                        return resolver;
                    }
                };
        return new RateLimitStartupGuard(provider);
    }

    @Test
    void missingResolverInProd_failsStartupWithGuidance() {
        assertThatThrownBy(() -> guardWith(null).requireCustomKeyResolver())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RateLimitKeyResolver")
                .hasMessageContaining("V-18");
    }

    @Test
    void presentResolver_silencesTheGuard() {
        assertThatCode(() -> guardWith(joinPoint -> "user:42").requireCustomKeyResolver())
                .doesNotThrowAnyException();
    }
}

package com.bhukkad.common.ratelimit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitAspectTest {

    private final RateLimitService service = new InMemoryRateLimitService();
    private final RateLimitAspect aspect = new RateLimitAspect(
            service, new org.springframework.beans.factory.ObjectProvider<RateLimitAspect.RateLimitKeyResolver>() {
                @Override
                public RateLimitAspect.RateLimitKeyResolver getIfAvailable() {
                    return joinPoint -> "user-1";
                }

                @Override
                public RateLimitAspect.RateLimitKeyResolver getObject() {
                    return getIfAvailable();
                }

                @Override
                public RateLimitAspect.RateLimitKeyResolver getObject(java.lang.Object... args) {
                    return getIfAvailable();
                }

                @Override
                public RateLimitAspect.RateLimitKeyResolver getIfUnique() {
                    return getIfAvailable();
                }
            });

    private ProceedingJoinPoint joinPointReturning(Object result) throws Throwable {
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        when(jp.proceed()).thenReturn(result);
        return jp;
    }

    @Test
    void allowedProceeds() throws Throwable {
        RateLimited annotation = rateLimited("order-create", 5, 60);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < 5; i++) {
            Object result = aspect.around(joinPointReturning(calls.incrementAndGet()), annotation);
            assertThat(result).isEqualTo(i + 1);
        }
    }

    @Test
    void deniedThrowsRateLimitExceeded() throws Throwable {
        RateLimited annotation = rateLimited("order-create", 2, 60);
        aspect.around(joinPointReturning("a"), annotation);
        aspect.around(joinPointReturning("b"), annotation);

        assertThatThrownBy(() -> aspect.around(joinPointReturning("c"), annotation))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("order-create");
    }

    @Test
    void exceptionInsideMethodPropagates() throws Throwable {
        RateLimited annotation = rateLimited("order-create", 5, 60);
        ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
        when(jp.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.around(jp, annotation))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    private RateLimited rateLimited(String bucket, int limit, int window) {
        return new RateLimited() {
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return RateLimited.class; }
            @Override public String bucket() { return bucket; }
            @Override public int limit() { return limit; }
            @Override public int windowSeconds() { return window; }
        };
    }
}

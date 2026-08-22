package com.bhukkad.chaos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaosFaultInjectionAspectTest {

    private ChaosProperties enabled(double latencyProb, long latencyMs, double failureProb) {
        ChaosProperties p = new ChaosProperties();
        p.setEnabled(true);
        p.setLatencyProbability(latencyProb);
        p.setLatencyMs(latencyMs);
        p.setFailureProbability(failureProb);
        return p;
    }

    @Test
    void disabledAspect_neverInjects() {
        ChaosProperties props = new ChaosProperties();
        props.setEnabled(false);
        props.setFailureProbability(1.0);
        assertFalse(props.shouldInjectFailure());
        assertFalse(props.shouldInjectLatency());
    }

    @Test
    void failureInjection_probabilityOne() {
        assertTrue(enabled(0, 0, 1.0).shouldInjectFailure());
    }

    @Test
    void latencyInjection_probabilityZero_neverInjects() {
        assertFalse(enabled(0.0, 500, 0).shouldInjectLatency());
    }

    @Test
    void latencyInjection_requiresLatencyMs() {
        ChaosProperties props = enabled(1.0, 0, 0);
        assertFalse(props.shouldInjectLatency());
    }

    @Test
    void aspect_injectsFailureWhenConfigured() throws Throwable {
        ChaosProperties props = enabled(0, 0, 1.0);
        ChaosFaultInjectionAspect aspect = new ChaosFaultInjectionAspect(props);

        org.aspectj.lang.ProceedingJoinPoint jp =
                org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);

        assertThrows(RuntimeException.class, () -> aspect.injectFault(jp, chaosFault("test")));
    }

    @Test
    void aspect_proceedsWhenDisabled() throws Throwable {
        ChaosProperties props = new ChaosProperties();
        props.setEnabled(false);
        ChaosFaultInjectionAspect aspect = new ChaosFaultInjectionAspect(props);

        org.aspectj.lang.ProceedingJoinPoint jp =
                org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(jp.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.injectFault(jp, chaosFault("test")));
    }

    private static ChaosFault chaosFault(String value) {
        return new ChaosFault() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return ChaosFault.class;
            }

            @Override
            public String value() {
                return value;
            }
        };
    }
}

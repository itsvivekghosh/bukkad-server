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

    @Test
    void aspect_enabledWithNoProbabilities_proceedsWithoutInterference() throws Throwable {
        ChaosProperties props = enabled(0, 500, 0);
        ChaosFaultInjectionAspect aspect = new ChaosFaultInjectionAspect(props);

        org.aspectj.lang.ProceedingJoinPoint jp =
                org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(jp.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.injectFault(jp, chaosFault("test")));

        org.mockito.Mockito.verify(jp).proceed();
    }

    @Test
    void aspect_failureInjection_reportsAnnotationValue_andNeverCallsTarget() throws Throwable {
        ChaosProperties props = enabled(0, 0, 1.0);
        ChaosFaultInjectionAspect aspect = new ChaosFaultInjectionAspect(props);

        org.aspectj.lang.ProceedingJoinPoint jp = proceedingJoinPointWithSignature();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> aspect.injectFault(jp, chaosFault("payment-call")));

        assertEquals("Chaos fault injected: payment-call", ex.getMessage());
        org.mockito.Mockito.verify(jp, org.mockito.Mockito.never()).proceed();
    }

    @Test
    void aspect_failureWinsEvenWhenLatencyAlsoArmed() throws Throwable {
        ChaosProperties props = enabled(1.0, 1, 1.0);
        ChaosFaultInjectionAspect aspect = new ChaosFaultInjectionAspect(props);

        org.aspectj.lang.ProceedingJoinPoint jp = proceedingJoinPointWithSignature();

        assertThrows(RuntimeException.class, () -> aspect.injectFault(jp, chaosFault("all-on")));

        org.mockito.Mockito.verify(jp, org.mockito.Mockito.never()).proceed();
    }

    @Test
    void aspect_latencyInjection_restoresInterruptFlag_andStillProceeds() throws Throwable {
        ChaosProperties props = enabled(1.0, 60_000, 0);
        ChaosFaultInjectionAspect aspect = new ChaosFaultInjectionAspect(props);

        org.aspectj.lang.ProceedingJoinPoint jp = proceedingJoinPointWithSignature();
        org.mockito.Mockito.when(jp.proceed()).thenReturn("ok");

        boolean originallyInterrupted = Thread.interrupted();
        Thread.currentThread().interrupt();
        try {
            // The pre-set interrupt flag makes Thread.sleep abort immediately,
            // so the InterruptedException catch block runs without waiting.
            assertEquals("ok", aspect.injectFault(jp, chaosFault("slow-call")));
            assertTrue(Thread.currentThread().isInterrupted(),
                    "aspect must re-set the interrupt flag after swallowing InterruptedException");
            org.mockito.Mockito.verify(jp).proceed();
        } finally {
            if (originallyInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void aspect_latencyInjection_completesSleep_andProceeds() throws Throwable {
        ChaosProperties props = enabled(1.0, 1, 0);
        ChaosFaultInjectionAspect aspect = new ChaosFaultInjectionAspect(props);

        org.aspectj.lang.ProceedingJoinPoint jp = proceedingJoinPointWithSignature();
        org.mockito.Mockito.when(jp.proceed()).thenReturn("ok");

        assertEquals("ok", aspect.injectFault(jp, chaosFault("brief-latency")));

        org.mockito.Mockito.verify(jp).proceed();
    }

    /**
     * The aspect logs {@code joinPoint.getSignature().toShortString()} on every
     * injected fault; a bare mock would NPE there before the fault is thrown.
     */
    private static org.aspectj.lang.ProceedingJoinPoint proceedingJoinPointWithSignature() {
        org.aspectj.lang.Signature signature =
                org.mockito.Mockito.mock(org.aspectj.lang.Signature.class);
        org.mockito.Mockito.when(signature.toShortString()).thenReturn("OrderService.place(..)");
        org.aspectj.lang.ProceedingJoinPoint jp =
                org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(jp.getSignature()).thenReturn(signature);
        return jp;
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

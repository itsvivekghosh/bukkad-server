package com.bhukkad.chaos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Chaos-engineering fault injection. When enabled, this aspect injects
 * artificial latency or failures into methods annotated with
 * {@link @ChaosFault}. Used for resilience testing (does the circuit
 * breaker trip? does the client retry?).
 */
@Slf4j
@Aspect
@Component
@Order(5)
@RequiredArgsConstructor
public class ChaosFaultInjectionAspect {

    private final ChaosProperties chaosProperties;

    @Around("@annotation(chaosFault)")
    public Object injectFault(ProceedingJoinPoint joinPoint, ChaosFault chaosFault) throws Throwable {
        if (!chaosProperties.isEnabled()) {
            return joinPoint.proceed();
        }

        if (chaosProperties.shouldInjectFailure()) {
            log.warn("CHAOS_FAULT_INJECTED | method={} | type=FAILURE",
                    joinPoint.getSignature().toShortString());
            throw new RuntimeException("Chaos fault injected: " + chaosFault.value());
        }

        if (chaosProperties.shouldInjectLatency()) {
            long latency = chaosProperties.getLatencyMs();
            log.warn("CHAOS_FAULT_INJECTED | method={} | type=LATENCY | ms={}",
                    joinPoint.getSignature().toShortString(), latency);
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return joinPoint.proceed();
    }
}

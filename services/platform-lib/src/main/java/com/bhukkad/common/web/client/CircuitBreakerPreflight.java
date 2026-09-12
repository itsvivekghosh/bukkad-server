package com.bhukkad.common.web.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.TreeSet;

/**
 * Circuit-breaker boot self-check (audit PERF-1/V-16, docs §VI.4.2 step 4).
 *
 * <p>On {@link ApplicationReadyEvent} it INFO-logs every mounted breaker
 * (the union of the context's {@link CircuitBreakerRegistry} bean and the
 * JVM-shared registry that {@link CircuitBreakerFilter}/{@link
 * PlatformWebClientBuilderFactory} mount WebClient breakers into) and —
 * prod-gated by the bean that registers this component — FAILS FAST when a
 * service declares {@code platform.web.outbound-targets} whose breaker never
 * mounted (silent zero-resilience traffic the V-16 audit flagged).</p>
 *
 * <p>The fail-fast is asserted only when a {@code CircuitBreakerRegistry}
 * bean exists: contexts without breaker infrastructure (slice tests,
 * auth-less contexts) no-op instead of failing on the gate itself —
 * the mounted list is still best-effort logged from the shared registry.</p>
 */
public class CircuitBreakerPreflight {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerPreflight.class);

    private final ObjectProvider<CircuitBreakerRegistry> registryProvider;
    private final CircuitBreakerPreflightProperties properties;

    public CircuitBreakerPreflight(ObjectProvider<CircuitBreakerRegistry> registryProvider,
                                   CircuitBreakerPreflightProperties properties) {
        this.registryProvider = registryProvider;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyMountedBreakers() {
        CircuitBreakerRegistry beanRegistry = registryProvider.getIfAvailable();

        TreeSet<String> mounted = new TreeSet<>();
        if (beanRegistry != null) {
            beanRegistry.getAllCircuitBreakers().forEach(cb -> mounted.add(cb.getName()));
        }
        mounted.addAll(sharedBreakerNames());

        if (beanRegistry == null) {
            // No registry bean = breaker infra not wired here (context-boot
            // safety): never fail-fast, only report what is observable.
            log.debug("Circuit-breaker self-check: no CircuitBreakerRegistry bean in this "
                    + "context — fail-fast skipped. Mounted breakers (shared registry): {}", mounted);
            return;
        }

        log.info("Circuit-breaker self-check: {} mounted breaker(s) for outbound calls: {}",
                mounted.size(), mounted);

        List<String> missing = properties.outboundTargets().stream()
                .filter(target -> !target.isBlank())
                .filter(target -> !mounted.contains(target))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "platform.web.outbound-targets " + missing
                            + " have no mounted circuit breaker (audit PERF-1/V-16). A breaker "
                            + "mounts when the target's platform WebClient is built — construct it "
                            + "from PlatformWebClientBuilderFactory at startup, register the instance "
                            + "on the CircuitBreakerRegistry, or remove the stale target from the "
                            + "configuration. Mounted now: " + mounted);
        }
    }

    /**
     * Names from the JVM-static breaker registry the platform WebClient
     * filters mount into. Loaded via reflection so contexts WITHOUT webflux
     * (where {@link CircuitBreakerFilter} itself cannot link) degrade to an
     * empty set instead of failing the boot.
     */
    private static java.util.Collection<String> sharedBreakerNames() {
        try {
            return CircuitBreakerFilter.sharedRegistry().getAllCircuitBreakers().stream()
                    .map(CircuitBreaker::getName)
                    .toList();
        } catch (LinkageError e) {
            return List.of();
        }
    }
}

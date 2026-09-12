package com.bhukkad.common.web.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Boot-time expectations for the platform circuit breakers (P1 PERF-1/V-16
 * follow-up, docs §VI.4.2 step 4: "log the mounted breaker list" + self-check).
 *
 * @param outboundTargets service names (WebClient breaker names — the
 *                        {@code PlatformWebClientBuilderFactory.forTarget}
 *                        / {@code @CircuitBreaker} instance names) that MUST
 *                        have a mounted breaker in this service. Empty (the
 *                        default) disables the fail-fast half of the
 *                        preflight; the mounted list is still logged.
 *                        Example: {@code platform.web.outbound-targets[0]=identity}.
 */
@ConfigurationProperties(prefix = "platform.web")
public record CircuitBreakerPreflightProperties(List<String> outboundTargets) {

    /** Null-safe accessor: an unconfigured property binds to an empty list. */
    public List<String> outboundTargets() {
        return outboundTargets == null ? List.of() : outboundTargets;
    }
}

package com.bhukkad.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup preflight (audit V-18 / G-3 matrix, prod profile): a service that
 * enforces rate limits must bucket on a REAL caller identity — the
 * auth-subject-or-client-IP {@link RateLimitAspect.RateLimitKeyResolver}
 * bean (the servlet stack ships {@code common.web.WebRateLimitKeyResolver}).
 *
 * <p>Without a resolver the aspect would fall back to a coarse shared
 * identifier, and one slow consumer could exhaust everyone's quota (the
 * platform-wide self-DoS the "default bucket" bug produced). Failing the
 * boot turns a silent abuse vector into a deployment error.</p>
 *
 * <p>Fires on {@link ApplicationReadyEvent} to match the other G-3 preflight
 * beans and to see the fully refreshed context.</p>
 */
@Component
@Profile("prod")
public class RateLimitStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(RateLimitStartupGuard.class);

    private final ObjectProvider<RateLimitAspect.RateLimitKeyResolver> keyResolvers;

    public RateLimitStartupGuard(ObjectProvider<RateLimitAspect.RateLimitKeyResolver> keyResolvers) {
        this.keyResolvers = keyResolvers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void requireCustomKeyResolver() {
        if (keyResolvers.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "prod requires a RateLimitAspect.RateLimitKeyResolver bean (auth subject or "
                            + "client IP) so rate-limit buckets are per caller (audit V-18/G-3); "
                            + "scan com.bhukkad.common.web (WebRateLimitKeyResolver) or register your own");
        }
        log.debug("Rate-limit startup guard passed: custom key resolver registered");
    }
}

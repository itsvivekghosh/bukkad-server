package com.bhukkad.common.security.authz;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry and evaluator for resource-level authorization policies.
 *
 * <p>Every evaluation records the {@code authorization_decisions} Micrometer
 * counter with {@code result} and {@code policy} tags. Denials are forwarded to
 * the platform's security-event logger. Unknown policies and policy failures
 * deny by default.</p>
 */
@Component
public class AuthorizationService {

    public static final String DECISIONS_METRIC = "authorization_decisions";

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final Map<String, AuthorizationPolicy> policies;
    private final MeterRegistry meterRegistry;
    private final AuthorizationDenialListener denialListener;

    @Autowired
    public AuthorizationService(List<AuthorizationPolicy> policies,
                                ObjectProvider<MeterRegistry> meterRegistryProvider,
                                ObjectProvider<AuthorizationDenialListener> denialListenerProvider) {
        this(policies,
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable(),
                denialListenerProvider == null ? null : denialListenerProvider.getIfUnique());
    }

    /** Direct construction for tests and non-component-scan consumers. */
    public AuthorizationService(Collection<AuthorizationPolicy> policies,
                                MeterRegistry meterRegistry,
                                AuthorizationDenialListener denialListener) {
        this.policies = indexPolicies(policies);
        this.meterRegistry = meterRegistry;
        this.denialListener = denialListener;
    }

    /**
     * Evaluates the named policy. A missing policy, unsupported request, or
     * policy exception is a denial.
     */
    public AuthorizationDecision check(String policyId, AuthorizationRequest request) {
        String requestedPolicy = policyId == null || policyId.isBlank() ? "unknown" : policyId;
        AuthorizationPolicy policy = policies.get(requestedPolicy);
        AuthorizationDecision decision;
        if (policy == null) {
            decision = AuthorizationDecision.deny("No authorization policy is registered", requestedPolicy);
        } else if (!policy.supports(request)) {
            decision = AuthorizationDecision.deny("Authorization policy does not support the request", policy.policyId());
        } else {
            try {
                AuthorizationDecision evaluated = policy.evaluate(request);
                decision = evaluated == null
                        ? AuthorizationDecision.deny("Authorization policy returned no decision", policy.policyId())
                        : evaluated;
            } catch (RuntimeException ex) {
                log.warn("Authorization policy evaluation failed policyId={} error={}",
                        policy.policyId(), ex.getMessage());
                decision = AuthorizationDecision.deny("Authorization policy evaluation failed", policy.policyId());
            }
        }
        recordDecision(decision);
        if (!decision.allowed()) {
            notifyDenial(decision, request);
        }
        return decision;
    }

    public boolean isAllowed(String policyId, AuthorizationRequest request) {
        return check(policyId, request).allowed();
    }

    private void recordDecision(AuthorizationDecision decision) {
        if (meterRegistry == null) {
            return;
        }
        String result = decision.allowed() ? "allowed" : "denied";
        String policy = decision.policyId() == null || decision.policyId().isBlank()
                ? "unknown"
                : decision.policyId();
        meterRegistry.counter(DECISIONS_METRIC,
                "result", result,
                "policy", policy).increment();
    }

    private void notifyDenial(AuthorizationDecision decision, AuthorizationRequest request) {
        if (denialListener == null) {
            return;
        }
        try {
            denialListener.onAuthorizationDenied(decision, request);
        } catch (RuntimeException ex) {
            log.warn("Authorization denial audit failed policyId={} error={}",
                    decision.policyId(), ex.getMessage());
        }
    }

    private static Map<String, AuthorizationPolicy> indexPolicies(Collection<AuthorizationPolicy> policies) {
        Map<String, AuthorizationPolicy> indexed = new LinkedHashMap<>();
        if (policies == null) {
            return Map.copyOf(indexed);
        }
        for (AuthorizationPolicy policy : policies) {
            if (policy == null || policy.policyId() == null || policy.policyId().isBlank()) {
                throw new IllegalArgumentException("Authorization policy must have a non-blank policyId");
            }
            AuthorizationPolicy previous = indexed.put(policy.policyId(), policy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate authorization policyId: " + policy.policyId());
            }
        }
        return Map.copyOf(indexed);
    }
}

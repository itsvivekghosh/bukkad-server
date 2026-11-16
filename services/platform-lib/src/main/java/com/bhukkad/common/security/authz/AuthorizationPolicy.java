package com.bhukkad.common.security.authz;

/**
 * A resource-level authorization policy.
 *
 * <p>Implementations are registered as Spring beans and selected by
 * {@link AuthorizationService#check(String, AuthorizationRequest)}. The
 * interface is intentionally independent of OPA so a Rego-backed implementation
 * can replace the local engine without changing callers.</p>
 */
public interface AuthorizationPolicy {

    /** Stable, low-cardinality identifier used for lookup and metric labels. */
    String policyId();

    /** Whether this policy can decide the supplied action/resource combination. */
    boolean supports(AuthorizationRequest request);

    /** Evaluates the request. Implementations should fail closed on invalid input. */
    AuthorizationDecision evaluate(AuthorizationRequest request);
}

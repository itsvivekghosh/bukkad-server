package com.bhukkad.common.security.authz;

/**
 * Result of evaluating one authorization policy.
 *
 * @param allowed whether access is permitted
 * @param reason human-readable explanation for the decision
 * @param policyId identifier of the policy that produced the decision
 */
public record AuthorizationDecision(boolean allowed, String reason, String policyId) {

    public static AuthorizationDecision allow(String reason, String policyId) {
        return new AuthorizationDecision(true, reason, policyId);
    }

    public static AuthorizationDecision deny(String reason, String policyId) {
        return new AuthorizationDecision(false, reason, policyId);
    }
}

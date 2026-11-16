package com.bhukkad.common.security.authz;

import java.util.Objects;

/**
 * Default ownership policy: the subject may access a resource when its
 * server-derived {@code ownerId} matches, while an administrator may access
 * any resource.
 *
 * <p>The generic implementation is usable directly for simple resources.
 * Domain policies can specialize the policy id and resource type without
 * duplicating the owner/admin rule.</p>
 */
public class ResourceOwnershipPolicy implements AuthorizationPolicy {

    public static final String DEFAULT_POLICY_ID = "resource-ownership";
    public static final String OWNER_ID_ATTRIBUTE = "ownerId";
    public static final String ADMIN_SCOPE = "ADMIN";

    private final String policyId;
    private final String resourceType;
    private final String adminScope;

    public ResourceOwnershipPolicy() {
        this(DEFAULT_POLICY_ID, null, ADMIN_SCOPE);
    }

    public ResourceOwnershipPolicy(String policyId, String resourceType) {
        this(policyId, resourceType, ADMIN_SCOPE);
    }

    public ResourceOwnershipPolicy(String policyId, String resourceType, String adminScope) {
        this.policyId = requireText(policyId, "policyId");
        this.resourceType = resourceType;
        this.adminScope = requireText(adminScope, "adminScope");
    }

    @Override
    public String policyId() {
        return policyId;
    }

    @Override
    public boolean supports(AuthorizationRequest request) {
        if (request == null) {
            return false;
        }
        return resourceType == null
                || resourceType.isBlank()
                || resourceType.equalsIgnoreCase(request.resourceType());
    }

    @Override
    public AuthorizationDecision evaluate(AuthorizationRequest request) {
        if (request == null) {
            return AuthorizationDecision.deny("Authorization request is required", policyId);
        }
        if (request.hasRoleOrScope(adminScope)) {
            return AuthorizationDecision.allow("Administrative bypass", policyId);
        }
        Object owner = request.attributes().get(OWNER_ID_ATTRIBUTE);
        if (owner == null || request.subject() == null) {
            return AuthorizationDecision.deny("Resource owner is not available to the subject", policyId);
        }
        if (sameIdentity(owner, request.subject())) {
            return AuthorizationDecision.allow("Subject owns the resource", policyId);
        }
        return AuthorizationDecision.deny("Subject does not own the resource", policyId);
    }

    private static boolean sameIdentity(Object owner, Long subject) {
        if (owner instanceof Number number) {
            return number.longValue() == subject.longValue();
        }
        return Objects.equals(String.valueOf(owner), String.valueOf(subject));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

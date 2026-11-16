package com.bhukkad.common.security.authz;

/**
 * Receives authorization denials for security-event auditing.
 *
 * <p>The platform's existing {@code SecurityEventLogger} implements this
 * interface. Keeping the core engine dependent on this small contract avoids
 * coupling reactive consumers to servlet-only logging classes.</p>
 */
public interface AuthorizationDenialListener {

    void onAuthorizationDenied(AuthorizationDecision decision, AuthorizationRequest request);
}

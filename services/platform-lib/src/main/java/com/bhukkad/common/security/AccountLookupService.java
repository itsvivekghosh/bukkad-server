package com.bhukkad.common.security;

import java.util.Optional;

/**
 * Resolves user accounts across role-segregated tables.
 *
 * <p>In a microservice deployment the canonical implementation lives in the
 * identity service; other modules should depend on this interface and obtain
 * an implementation through configuration.</p>
 */
public interface AccountLookupService {

    Optional<UserSummary> byEmail(String email);

    Optional<UserSummary> byPhoneNumber(String phoneNumber);

    Optional<UserSummary> byIdentifier(String identifier);

    boolean existsAnywhereByEmail(String email);

    boolean existsAnywhereByPhoneNumber(String phoneNumber);

    record UserSummary(Long id, String email, String phoneNumber, String role) {
    }
}

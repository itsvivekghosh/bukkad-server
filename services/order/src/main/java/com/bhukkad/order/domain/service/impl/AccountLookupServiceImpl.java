package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.security.AccountLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Stub account lookup until the identity service exposes cross-role lookup
 * endpoints. All methods return empty results so callers degrade gracefully.
 */
@Service
@RequiredArgsConstructor
public class AccountLookupServiceImpl implements AccountLookupService {

    @Override
    public Optional<UserSummary> byEmail(String email) {
        return Optional.empty();
    }

    @Override
    public Optional<UserSummary> byPhoneNumber(String phoneNumber) {
        return Optional.empty();
    }

    @Override
    public Optional<UserSummary> byIdentifier(String identifier) {
        return Optional.empty();
    }

    @Override
    public boolean existsAnywhereByEmail(String email) {
        return false;
    }

    @Override
    public boolean existsAnywhereByPhoneNumber(String phoneNumber) {
        return false;
    }
}

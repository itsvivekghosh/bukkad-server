package com.bhukkad.referral.service;

import com.bhukkad.referral.dto.response.ReferralInfoResponse;

/**
 * Personal referral codes and rewards summaries.
 */
public interface ReferralService {

    /**
     * Returns the customer's referral code, referral count and earned bonuses.
     * Auto-generates and persists a code on first access.
     */
    ReferralInfoResponse getReferralInfo(Long customerId);

    /** True when the code belongs to an existing referral code. */
    boolean isValidReferralCode(String code);

    /** Generates (if absent) and persists the customer's referral code. */
    String generateAndSaveReferralCode(Long customerId);

    /**
     * Applies a referral code to a new customer signup: links the referrer and
     * bumps their counters. Called by the registration flow (via the monolith
     * or identity service) once signup lands in this domain.
     */
    void applyReferral(Long newCustomerId, String customerEmail, String referralCodeInput);
}
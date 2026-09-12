package com.bhukkad.referral.domain.service;

import com.bhukkad.referral.api.dto.response.ReferralInfoResponse;

/**
 * Personal referral codes and rewards summaries. Referral is the SINGLE code
 * generator (ADR-005): growth and identity delegate here.
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
     * Applies a referral code to a new customer signup: binds the referee
     * (idempotently — a customer already carrying {@code referredBy} is never
     * re-bound) and credits the apply reward exactly once. Called by the
     * registration flow (identity service) once signup lands in this domain.
     */
    ApplyReferralOutcome applyReferral(Long newCustomerId, String customerEmail, String referralCodeInput);

    /**
     * Marks the referral lifecycle complete (e.g. the referee's first order)
     * and credits the completion reward exactly once, idempotent by
     * {@code orderId} through the reward ledger.
     */
    CompletionOutcome completeReferral(Long referredCustomerId, Long orderId);

    /** Result of {@link #applyReferral(Long, String, String)}. */
    record ApplyReferralOutcome(Long referrerCustomerId, boolean firstBinding, boolean applied) {

        public static ApplyReferralOutcome firstBinding(Long referrerCustomerId) {
            return new ApplyReferralOutcome(referrerCustomerId, true, true);
        }

        public static ApplyReferralOutcome alreadyBound(Long referrerCustomerId) {
            return new ApplyReferralOutcome(referrerCustomerId, false, true);
        }

        public static ApplyReferralOutcome notApplied() {
            return new ApplyReferralOutcome(null, false, false);
        }
    }

    /** Result of {@link #completeReferral(Long, Long)}. */
    record CompletionOutcome(Long referrerCustomerId, boolean completed) {

        public static CompletionOutcome completed(Long referrerCustomerId) {
            return new CompletionOutcome(referrerCustomerId, true);
        }

        public static CompletionOutcome alreadyCompleted() {
            return new CompletionOutcome(null, false);
        }

        public static CompletionOutcome noBinding() {
            return new CompletionOutcome(null, false);
        }
    }
}

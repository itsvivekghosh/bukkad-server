package com.bhukkad.referral.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Internal payload applying a referral code to a new customer signup. */
public record ApplyReferralRequest(
        @NotBlank String referralCode,
        Long customerId,
        String customerEmail
) {}
package com.bhukkad.referral.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/referrals/validate}. */
public record ReferralValidateRequest(
    @NotBlank(message = "referralCode must not be blank")
    String referralCode
) {}

package com.bhukkad.referral.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Internal payload attributing a new signup to an affiliate code. */
public record AffiliateSignupRequest(
        @NotBlank String code,
        Long customerId,
        String customerEmail
) {}
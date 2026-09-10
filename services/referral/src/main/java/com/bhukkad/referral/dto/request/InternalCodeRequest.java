package com.bhukkad.referral.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Internal payload for the single-generator code API (ADR-005). */
public record InternalCodeRequest(
        @NotNull @Positive Long customerId
) {}

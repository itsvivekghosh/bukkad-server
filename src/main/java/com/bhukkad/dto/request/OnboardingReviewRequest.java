package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Admin decision on a dark kitchen onboarding application. */
@Data
public class OnboardingReviewRequest {

    @NotNull(message = "Approved flag is required")
    private Boolean approved;

    /** Required when rejecting; optional when approving. */
    private String reason;
}

package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Manual review decision on a fraud event. */
@Data
public class FraudReviewActionRequest {

    @NotNull(message = "Action is required")
    private String action;

    private String notes;
}

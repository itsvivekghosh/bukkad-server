package com.bhukkad.identity.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SubscribeMembershipRequest {
    @NotNull(message = "Plan ID is required")
    private Long planId;
}

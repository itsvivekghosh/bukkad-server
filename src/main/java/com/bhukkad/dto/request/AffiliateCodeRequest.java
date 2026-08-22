package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Admin request to create or update an affiliate/influencer code. */
@Data
public class AffiliateCodeRequest {

    @NotBlank(message = "Code is required")
    private String code;

    @NotBlank(message = "Name is required")
    private String name;

    private String channel;

    @NotNull(message = "Reward amount is required")
    private Double rewardAmount;

    private Boolean isActive;
}

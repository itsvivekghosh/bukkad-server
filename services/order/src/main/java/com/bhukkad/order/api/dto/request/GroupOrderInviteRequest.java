package com.bhukkad.order.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GroupOrderInviteRequest {

    @NotBlank(message = "Phone is required")
    @Size(max = 15, message = "Phone must be at most 15 characters")
    private String phone;
}

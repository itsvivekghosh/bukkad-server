package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GiftCardRedeemRequest {
    @NotBlank(message = "Gift card code is required")
    private String code;
}
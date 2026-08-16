package com.bhukkad.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class GiftCardPurchaseRequest {
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "100.0", message = "Minimum gift card amount is 100")
    private Double amount;

    @NotBlank(message = "Recipient email is required")
    private String recipientEmail;

    @NotBlank(message = "Recipient name is required")
    private String recipientName;

    private String message;

    @NotNull(message = "Expiry date is required")
    private LocalDate expiresAt;
}
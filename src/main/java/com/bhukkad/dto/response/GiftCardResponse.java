package com.bhukkad.dto.response;

import com.bhukkad.entity.GiftCard;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GiftCardResponse {
    private Long id;
    private String code;
    private Double amount;
    private Double balance;
    private GiftCard.Status status;
    private String recipientEmail;
    private String recipientName;
    private String message;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime redeemedAt;
}
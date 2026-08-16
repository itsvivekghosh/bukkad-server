package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WalletTransactionResponse {
    private Long id;
    private String type;
    private Double amount;
    private Double balanceAfter;
    private String description;
    private String createdAt;
}

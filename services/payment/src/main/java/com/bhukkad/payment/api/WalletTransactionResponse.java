package com.bhukkad.payment.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransactionResponse {

    private Long id;
    private Long customerId;
    private String type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String reference;
    private String createdAt;
}

package com.bhukkad.payment.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class WalletTopUpRequest {

    @Positive
    private Long customerId;

    @NotNull
    private BigDecimal amount;
}

package com.bhukkad.payment.mapper;

import com.bhukkad.payment.api.DisputeResponse;
import com.bhukkad.payment.api.PaymentResponse;
import com.bhukkad.payment.api.RestaurantSettlementResponse;
import com.bhukkad.payment.api.WalletResponse;
import com.bhukkad.payment.api.WalletTransactionResponse;
import com.bhukkad.payment.domain.Dispute;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.RestaurantSettlement;
import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.domain.WalletTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class PaymentMapper {

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public PaymentResponse toPaymentResponse(Payment payment) {
        if (payment == null) return null;
        return PaymentResponse.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .customerId(payment.getCustomerId())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .amount(payment.getAmount())
                .walletAmount(payment.getWalletAmount())
                .gatewayAmount(payment.getGatewayAmount())
                .gatewayOrderId(payment.getGatewayOrderId())
                .gatewayPaymentId(payment.getGatewayPaymentId())
                .transactionId(payment.getTransactionId())
                .purpose(payment.getPurpose())
                .createdAt(formatTs(payment.getCreatedAt()))
                .completedAt(formatTs(payment.getCompletedAt()))
                .build();
    }

    public WalletResponse toWalletResponse(WalletBalance balance) {
        if (balance == null) return null;
        return WalletResponse.builder()
                .id(balance.getId())
                .customerId(balance.getCustomerId())
                .balance(balance.getBalance())
                .updatedAt(formatTs(balance.getUpdatedAt()))
                .build();
    }

    public WalletTransactionResponse toWalletTransactionResponse(WalletTransaction tx) {
        if (tx == null) return null;
        return WalletTransactionResponse.builder()
                .id(tx.getId())
                .customerId(tx.getCustomerId())
                .type(tx.getType())
                .amount(tx.getAmount())
                .balanceAfter(tx.getBalanceAfter())
                .reference(tx.getReference())
                .createdAt(formatTs(tx.getCreatedAt()))
                .build();
    }

    public RestaurantSettlementResponse toRestaurantSettlementResponse(RestaurantSettlement settlement) {
        if (settlement == null) return null;
        return RestaurantSettlementResponse.builder()
                .id(settlement.getId())
                .settlementRunId(settlement.getSettlementRunId())
                .restaurantId(settlement.getRestaurantId())
                .orderId(null)
                .orderCount(settlement.getOrderCount())
                .grossAmount(settlement.getGrossAmount())
                .commission(settlement.getCommission())
                .netAmount(settlement.getNetAmount())
                .status(settlement.getStatus())
                .createdAt(formatTs(settlement.getCreatedAt()))
                .build();
    }

    public DisputeResponse toDisputeResponse(Dispute dispute) {
        if (dispute == null) return null;
        return DisputeResponse.builder()
                .id(dispute.getId())
                .paymentId(dispute.getPaymentId())
                .customerId(dispute.getCustomerId())
                .orderId(dispute.getOrderId())
                .status(dispute.getStatus())
                .resolution(dispute.getResolution())
                .refundAmount(dispute.getAmount())
                .createdAt(formatTs(dispute.getCreatedAt()))
                .build();
    }

    private static String formatTs(LocalDateTime ts) {
        return ts != null ? ts.format(TS_FORMATTER) : null;
    }
}

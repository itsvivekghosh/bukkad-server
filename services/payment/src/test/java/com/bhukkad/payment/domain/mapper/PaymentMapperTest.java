package com.bhukkad.payment.domain.mapper;

import com.bhukkad.payment.api.dto.response.PaymentResponse;
import com.bhukkad.payment.api.dto.response.RestaurantSettlementResponse;
import com.bhukkad.payment.api.dto.response.WalletResponse;
import com.bhukkad.payment.api.dto.response.WalletTransactionResponse;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.entity.RestaurantSettlement;
import com.bhukkad.payment.domain.entity.WalletBalance;
import com.bhukkad.payment.domain.entity.WalletTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMapperTest {

    private final PaymentMapper mapper = new PaymentMapper();

    @Test
    void toPaymentResponse_mapsEveryField() {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setOrderId(2L);
        payment.setCustomerId(3L);
        payment.setPaymentMethod("UPI");
        payment.setStatus("SETTLED");
        payment.setAmount(new BigDecimal("150.00"));
        payment.setWalletAmount(new BigDecimal("50.00"));
        payment.setGatewayAmount(new BigDecimal("100.00"));
        payment.setGatewayOrderId("order_gw");
        payment.setGatewayPaymentId("pay_gw");
        payment.setTransactionId("txn_1");
        payment.setPurpose("ORDER");
        payment.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        payment.setCompletedAt(LocalDateTime.of(2026, 1, 2, 3, 6, 7));

        PaymentResponse response = mapper.toPaymentResponse(payment);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getOrderId()).isEqualTo(2L);
        assertThat(response.getCustomerId()).isEqualTo(3L);
        assertThat(response.getPaymentMethod()).isEqualTo("UPI");
        assertThat(response.getStatus()).isEqualTo("SETTLED");
        assertThat(response.getAmount()).isEqualByComparingTo("150.00");
        assertThat(response.getWalletAmount()).isEqualByComparingTo("50.00");
        assertThat(response.getGatewayAmount()).isEqualByComparingTo("100.00");
        assertThat(response.getGatewayOrderId()).isEqualTo("order_gw");
        assertThat(response.getGatewayPaymentId()).isEqualTo("pay_gw");
        assertThat(response.getTransactionId()).isEqualTo("txn_1");
        assertThat(response.getPurpose()).isEqualTo("ORDER");
        assertThat(response.getCreatedAt()).isEqualTo("2026-01-02T03:04:05");
        assertThat(response.getCompletedAt()).isEqualTo("2026-01-02T03:06:07");
    }

    @Test
    void toPaymentResponse_nullTimestampsRenderNull() {
        Payment payment = new Payment();
        payment.setId(9L);

        PaymentResponse response = mapper.toPaymentResponse(payment);

        assertThat(response.getId()).isEqualTo(9L);
        assertThat(response.getCreatedAt()).isNull();
        assertThat(response.getCompletedAt()).isNull();
    }

    @Test
    void toPaymentResponse_null_returnsNull() {
        assertThat(mapper.toPaymentResponse(null)).isNull();
    }

    @Test
    void toWalletResponse_mapsAndNull() {
        WalletBalance balance = new WalletBalance();
        balance.setId(4L);
        balance.setCustomerId(5L);
        balance.setBalance(new BigDecimal("42.50"));
        balance.setUpdatedAt(LocalDateTime.of(2026, 5, 5, 10, 0));

        WalletResponse response = mapper.toWalletResponse(balance);

        assertThat(response.getId()).isEqualTo(4L);
        assertThat(response.getCustomerId()).isEqualTo(5L);
        assertThat(response.getBalance()).isEqualByComparingTo("42.50");
        assertThat(response.getUpdatedAt()).isEqualTo("2026-05-05T10:00:00");

        assertThat(mapper.toWalletResponse(null)).isNull();
    }

    @Test
    void toWalletTransactionResponse_mapsAndNull() {
        WalletTransaction tx = new WalletTransaction();
        tx.setId(6L);
        tx.setCustomerId(7L);
        tx.setType("CREDIT");
        tx.setAmount(new BigDecimal("20.00"));
        tx.setBalanceAfter(new BigDecimal("120.00"));
        tx.setReference("topup:1");
        tx.setCreatedAt(LocalDateTime.of(2026, 6, 1, 8, 30));

        WalletTransactionResponse response = mapper.toWalletTransactionResponse(tx);

        assertThat(response.getId()).isEqualTo(6L);
        assertThat(response.getCustomerId()).isEqualTo(7L);
        assertThat(response.getType()).isEqualTo("CREDIT");
        assertThat(response.getAmount()).isEqualByComparingTo("20.00");
        assertThat(response.getBalanceAfter()).isEqualByComparingTo("120.00");
        assertThat(response.getReference()).isEqualTo("topup:1");
        assertThat(response.getCreatedAt()).isEqualTo("2026-06-01T08:30:00");

        assertThat(mapper.toWalletTransactionResponse(null)).isNull();
    }

    @Test
    void toRestaurantSettlementResponse_mapsAndNull() {
        RestaurantSettlement settlement = new RestaurantSettlement();
        settlement.setId(8L);
        settlement.setSettlementRunId(11L);
        settlement.setRestaurantId(12L);
        settlement.setOrderCount(4);
        settlement.setGrossAmount(new BigDecimal("1000.00"));
        settlement.setCommission(new BigDecimal("180.00"));
        settlement.setNetAmount(new BigDecimal("820.00"));
        settlement.setStatus("COMPLETED");
        settlement.setCreatedAt(LocalDateTime.of(2026, 7, 1, 0, 0));

        RestaurantSettlementResponse response = mapper.toRestaurantSettlementResponse(settlement);

        assertThat(response.getId()).isEqualTo(8L);
        assertThat(response.getSettlementRunId()).isEqualTo(11L);
        assertThat(response.getRestaurantId()).isEqualTo(12L);
        assertThat(response.getOrderCount()).isEqualTo(4);
        assertThat(response.getGrossAmount()).isEqualByComparingTo("1000.00");
        assertThat(response.getCommission()).isEqualByComparingTo("180.00");
        assertThat(response.getNetAmount()).isEqualByComparingTo("820.00");
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getCreatedAt()).isEqualTo("2026-07-01T00:00:00");

        assertThat(mapper.toRestaurantSettlementResponse(null)).isNull();
    }
}

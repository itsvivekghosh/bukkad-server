package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.DunningRun;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.RestaurantSettlement;
import com.bhukkad.payment.service.AutoRefundService;
import com.bhukkad.payment.service.CommissionTierService;
import com.bhukkad.payment.service.DisputeService;
import com.bhukkad.payment.service.DunningService;
import com.bhukkad.payment.service.SettlementService;
import com.bhukkad.payment.domain.SettlementRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOperationsControllerTest {

    @Mock private AutoRefundService refundService;
    @Mock private DunningService dunningService;
    @Mock private CommissionTierService commissionTierService;
    @Mock private DisputeService disputeService;
    @Mock private SettlementService settlementService;
    @Mock private com.bhukkad.payment.mapper.PaymentMapper paymentMapper;
    @InjectMocks private PaymentOperationsController controller;

    @Test
    void refund_delegatesToRefundService() {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setStatus(Payment.STATUS_REFUNDED);
        when(refundService.refund(1L, "customer request")).thenReturn(payment);
        PaymentResponse response = PaymentResponse.builder()
                .id(1L).status(Payment.STATUS_REFUNDED).build();
        when(paymentMapper.toPaymentResponse(payment)).thenReturn(response);

        assertThat(controller.refund(1L, "customer request").getStatus())
                .isEqualTo(Payment.STATUS_REFUNDED);
        verify(refundService).refund(1L, "customer request");
    }

    @Test
    void dunning_withScheduledAt_usesProvidedTime() {
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 2, 1, 10, 0);
        DunningRun run = new DunningRun();
        run.setScheduledAt(scheduledAt);
        when(dunningService.scheduleRetry(1L, 2, scheduledAt)).thenReturn(run);

        assertThat(controller.dunning(1L, 2, scheduledAt).getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    void dunning_withoutScheduledAt_defaultsToOneHourAhead() {
        when(dunningService.scheduleRetry(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    DunningRun run = new DunningRun();
                    run.setScheduledAt(inv.getArgument(2));
                    return run;
                });

        DunningRun result = controller.dunning(1L, 1, null);

        assertThat(result.getScheduledAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void pendingDunning_returnsPendingRuns() {
        DunningRun run = new DunningRun();
        run.setStatus("SCHEDULED");
        when(dunningService.pending()).thenReturn(List.of(run));

        assertThat(controller.pendingDunning()).hasSize(1);
    }

    @Test
    void tiers_returnsActiveTiers() {
        when(commissionTierService.active()).thenReturn(List.of());

        assertThat(controller.tiers()).isEmpty();
        verify(commissionTierService).active();
    }

    @Test
    void createTier_delegatesToCommissionService() {
        when(commissionTierService.create(5, 20, new BigDecimal("7.00")))
                .thenReturn(null);

        controller.createTier(5, 20, new BigDecimal("7.00"));
        verify(commissionTierService).create(5, 20, new BigDecimal("7.00"));
    }

    @Test
    void settle_delegatesToSettlementService() {
        RestaurantSettlement settlement = new RestaurantSettlement();
        settlement.setId(1L);
        settlement.setRestaurantId(42L);
        SettlementService.SettlementResult result = new SettlementService.SettlementResult(
                new SettlementRun(), settlement);
        when(settlementService.run(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("300.00")))).thenReturn(result);
        RestaurantSettlementResponse response = RestaurantSettlementResponse.builder()
                .id(1L).restaurantId(42L).build();
        when(paymentMapper.toRestaurantSettlementResponse(settlement)).thenReturn(response);

        assertThat(controller.settle(42L, 3, new BigDecimal("300.00"))).isSameAs(response);
    }

    @Test
    void webhook_acknowledgesEvent() {
        String ack = controller.webhook(1L, "capture");

        assertThat(ack).contains("\"received\":true", "\"paymentId\":1", "\"event\":\"capture\"");
    }
}

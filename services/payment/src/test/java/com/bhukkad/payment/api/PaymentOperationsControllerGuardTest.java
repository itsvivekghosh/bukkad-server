package com.bhukkad.payment.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.entity.RestaurantSettlement;
import com.bhukkad.payment.domain.entity.SettlementRun;
import com.bhukkad.payment.domain.mapper.PaymentMapper;
import com.bhukkad.payment.domain.service.AutoRefundService;
import com.bhukkad.payment.domain.service.CommissionTierService;
import com.bhukkad.payment.domain.service.DunningService;
import com.bhukkad.payment.domain.service.SettlementService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentOperationsControllerGuardTest {

    @Mock private AutoRefundService refundService;
    @Mock private DunningService dunningService;
    @Mock private CommissionTierService commissionTierService;
    @Mock private SettlementService settlementService;
    @Mock private PaymentMapper paymentMapper;

    private PaymentOperationsController controller(ObjectMapper objectMapper) {
        return new PaymentOperationsController(
                refundService, dunningService, commissionTierService,
                settlementService, paymentMapper, objectMapper);
    }

    private PaymentOperationsController controller;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        controller = controller(new ObjectMapper());
    }

    @Test
    void dunning_rejectsNonPositiveAttempt() {
        assertThatThrownBy(() -> controller.dunning(1L, 0, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void createTier_rejectsNegativeCommission() {
        assertThatThrownBy(() -> controller.createTier(0, 10, new BigDecimal("-0.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("0 and 100");
    }

    @Test
    void createTier_rejectsCommissionAboveHundred() {
        assertThatThrownBy(() -> controller.createTier(0, 10, new BigDecimal("100.01")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createTier_boundaryValuesAccepted() {
        controller.createTier(0, 10, BigDecimal.ZERO);
        controller.createTier(0, 10, new BigDecimal("100"));

        org.mockito.Mockito.verify(commissionTierService,
                org.mockito.Mockito.times(2)).create(anyInt(), any(), any());
    }

    @Test
    void settle_rejectsNegativeOrderCount() {
        assertThatThrownBy(() -> controller.settle(5L, -1, BigDecimal.ONE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("orderCount");
    }

    @Test
    void settleRiderPayouts_reportsSettlementIdWhenRunCreatesRow() {
        RestaurantSettlement settlement = new RestaurantSettlement();
        settlement.setId(17L);
        when(settlementService.run(any(), anyLong(), anyInt(), any()))
                .thenReturn(new SettlementService.SettlementResult(
                        new SettlementRun(), settlement));

        Map<String, Object> body = controller.settleRiderPayouts(3L);

        assertThat(body)
                .containsEntry("agentId", 3L)
                .containsEntry("settled", true)
                .containsEntry("settlementId", 17L);
    }

    @Test
    void settleRiderPayouts_nullSettlement_yieldsNullId() {
        when(settlementService.run(any(), anyLong(), anyInt(), any()))
                .thenReturn(new SettlementService.SettlementResult(
                        new SettlementRun(), null));

        assertThat(controller.settleRiderPayouts(3L)).containsEntry("settlementId", null);
    }

    @Test
    void webhook_nullEventEchoesEmptyString() {
        String body = controller.webhook(1L, null);

        assertThat(body).contains("\"received\":true")
                .contains("\"event\":\"\"")
                .contains("\"paymentId\":1");
    }

    @Test
    void webhook_serializerFailure_fallsBackToMinimalAck() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("boom") {
                });

        assertThat(controller(broken).webhook(2L, "payment.captured"))
                .isEqualTo("{\"received\":true}");
    }
}

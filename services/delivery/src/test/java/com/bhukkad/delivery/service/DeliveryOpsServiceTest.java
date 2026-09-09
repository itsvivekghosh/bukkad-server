package com.bhukkad.delivery.service;

import com.bhukkad.delivery.client.PaymentServiceClient;
import com.bhukkad.delivery.domain.OrderDeliveryProof;
import com.bhukkad.delivery.domain.OrderDeliveryProofRepository;
import com.bhukkad.delivery.domain.ZoneSurgeRule;
import com.bhukkad.delivery.domain.ZoneSurgeRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delivery ops depth: rider earnings, delivery proofs, zone surge rules.
 * Rider earnings are delegated to payment service.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryOpsServiceTest {

    @Mock private OrderDeliveryProofRepository proofRepository;
    @Mock private ZoneSurgeRuleRepository surgeRepository;
    @Mock private PaymentServiceClient paymentClient;
    @InjectMocks private DeliveryOpsService service;

    @Test
    void recordEarning_delegatesToPaymentService() {
        Map<String, Object> expectedResponse = Map.of(
                "id", 1L,
                "agentId", 5L,
                "orderId", 42L,
                "amount", new BigDecimal("60.00"),
                "status", "EARNED"
        );
        when(paymentClient.recordEarning(eq(5L), eq(42L), eq(new BigDecimal("60.00"))))
                .thenReturn(expectedResponse);

        Map<String, Object> result = service.recordEarning(5L, 42L, new BigDecimal("60.00"));

        assertThat(result.get("agentId")).isEqualTo(5L);
        assertThat(result.get("orderId")).isEqualTo(42L);
        assertThat(result.get("status")).isEqualTo("EARNED");
        verify(paymentClient).recordEarning(5L, 42L, new BigDecimal("60.00"));
    }

    @Test
    void recordProof_persistsPhotoNotesSignature() {
        when(proofRepository.save(any(OrderDeliveryProof.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderDeliveryProof proof = service.recordProof(42L, "s3://proof/42.jpg", "doorstep", "sig-abc");

        assertThat(proof.getOrderId()).isEqualTo(42L);
        assertThat(proof.getPhotoUrl()).isEqualTo("s3://proof/42.jpg");
        assertThat(proof.getNotes()).isEqualTo("doorstep");
        assertThat(proof.getSignature()).isEqualTo("sig-abc");
        assertThat(proof.getCreatedAt()).isNotNull();
        verify(proofRepository).save(proof);
    }

    @Test
    void earnings_delegatesToPaymentService() {
        List<Map<String, Object>> expectedEarnings = List.of(
                Map.of("id", 1L, "agentId", 5L, "amount", new BigDecimal("60.00")),
                Map.of("id", 2L, "agentId", 5L, "amount", new BigDecimal("45.00"))
        );
        when(paymentClient.getEarnings(5L)).thenReturn(expectedEarnings);

        List<Map<String, Object>> result = service.earnings(5L);

        assertThat(result).hasSize(2);
        verify(paymentClient).getEarnings(5L);
    }

    @Test
    void activeSurgeRules_returnsOnlyActiveForZone() {
        when(surgeRepository.findByZoneIdAndActiveTrue(3L)).thenReturn(List.of(new ZoneSurgeRule()));

        assertThat(service.activeSurgeRules(3L)).hasSize(1);
        verify(surgeRepository).findByZoneIdAndActiveTrue(3L);
    }
}

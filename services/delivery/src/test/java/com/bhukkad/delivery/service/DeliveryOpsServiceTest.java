package com.bhukkad.delivery.service;

import com.bhukkad.delivery.domain.OrderDeliveryProof;
import com.bhukkad.delivery.domain.OrderDeliveryProofRepository;
import com.bhukkad.delivery.domain.RiderEarning;
import com.bhukkad.delivery.domain.RiderEarningRepository;
import com.bhukkad.delivery.domain.ZoneSurgeRule;
import com.bhukkad.delivery.domain.ZoneSurgeRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delivery ops depth: rider earnings, delivery proofs, zone surge rules.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryOpsServiceTest {

    @Mock private RiderEarningRepository earningRepository;
    @Mock private OrderDeliveryProofRepository proofRepository;
    @Mock private ZoneSurgeRuleRepository surgeRepository;
    @InjectMocks private DeliveryOpsService service;

    @Test
    void recordEarning_savesPendingEarning() {
        when(earningRepository.save(any(RiderEarning.class))).thenAnswer(inv -> inv.getArgument(0));

        RiderEarning earning = service.recordEarning(5L, 42L, new BigDecimal("60.00"));

        assertThat(earning.getAgentId()).isEqualTo(5L);
        assertThat(earning.getOrderId()).isEqualTo(42L);
        assertThat(earning.getAmount()).isEqualByComparingTo("60.00");
        assertThat(earning.getStatus()).isEqualTo("PENDING");
        verify(earningRepository).save(earning);
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
    void earnings_returnsAgentHistory() {
        when(earningRepository.findByAgentId(5L)).thenReturn(List.of(new RiderEarning(), new RiderEarning()));

        assertThat(service.earnings(5L)).hasSize(2);
    }

    @Test
    void activeSurgeRules_returnsOnlyActiveForZone() {
        when(surgeRepository.findByZoneIdAndActiveTrue(3L)).thenReturn(List.of(new ZoneSurgeRule()));

        assertThat(service.activeSurgeRules(3L)).hasSize(1);
        verify(surgeRepository).findByZoneIdAndActiveTrue(3L);
    }
}

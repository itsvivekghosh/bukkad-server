package com.bhukkad.fraud;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.User;
import com.bhukkad.repository.FraudEventRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudRiskScoringServiceTest {

    @Mock
    private com.bhukkad.repository.FraudEventRepository fraudEventRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;

    private FraudScoringProperties properties;
    private FraudRiskScoringService service;

    @BeforeEach
    void setUp() {
        properties = new FraudScoringProperties();
        service = new FraudRiskScoringService(properties, fraudEventRepository,
                orderRepository, userRepository);
        lenient().when(orderRepository.countByCustomerIdAndCreatedAtAfter(anyLong(), any()))
                .thenReturn(0L);
        lenient().when(fraudEventRepository.countByEventTypeAndCustomerIdAndCreatedAtAfter(
                org.mockito.ArgumentMatchers.anyString(), anyLong(), any())).thenReturn(0L);
    }

    @Test
    void scoreOrder_returnsNoScoreWhenDisabled() {
        properties.setEnabled(false);
        FraudRiskAssessment assessment = service.scoreOrder(1L, 500);
        assertFalse(assessment.scored());
        assertFalse(service.shouldBlock(assessment, 99L));
    }

    @Test
    void scoreOrder_lowRiskForEstablishedCustomerInDaytime() {
        User user = new Customer();
        user.setCreatedAt(LocalDateTime.now().minusYears(2));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        FraudRiskAssessment assessment = service.scoreOrder(1L, 400);
        assertTrue(assessment.score() < properties.getReviewThreshold(),
                "expected low score but got " + assessment.score());
        assertEquals(FraudRiskAssessment.Level.LOW, assessment.level());
        assertFalse(service.shouldBlock(assessment, null));
    }

    @Test
    void scoreOrder_flagsBrandNewAccountWithFraudHistoryAndVelocity() {
        // Late-night window is time-dependent — force the hour to be "normal" by
        // scoring with features that dominate regardless of the clock.
        User user = new Customer();
        user.setCreatedAt(LocalDateTime.now().minusHours(2)); // brand new
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(orderRepository.countByCustomerIdAndCreatedAtAfter(eq(2L), any()))
                .thenReturn(9L); // burst velocity
        when(fraudEventRepository.countByEventTypeAndCustomerIdAndCreatedAtAfter(
                eq(FraudEventTypes.ORDER_CREATE), eq(2L), any())).thenReturn(3L);

        FraudRiskAssessment assessment = service.scoreOrder(2L, 3500);
        assertTrue(assessment.reasons().stream().anyMatch(r -> r.contains("velocity")));
        assertTrue(assessment.reasons().stream().anyMatch(r -> r.startsWith("account_age_lt_1d")));
        assertTrue(assessment.score() >= properties.getReviewThreshold(),
                "expected high-risk score, got " + assessment.score());
    }

    @Test
    void shouldBlock_neverBlocksWhenEnforcementDisabled() {
        properties.setEnforcementEnabled(false);
        FraudRiskAssessment critical =
                new FraudRiskAssessment(true, 95, FraudRiskAssessment.Level.CRITICAL,
                        List.of("account_age_lt_1d"));
        assertFalse(service.shouldBlock(critical, null));
    }

    @Test
    void shouldBlock_blocksWhenEnforcementEnabledAndScoreCritical() {
        properties.setEnforcementEnabled(true);
        FraudRiskAssessment critical =
                new FraudRiskAssessment(true, 90, FraudRiskAssessment.Level.CRITICAL,
                        List.of("prior_fraud_events_30d=3"));
        assertTrue(service.shouldBlock(critical, null));
    }

    @Test
    void recordReviewFlag_persistsEventBestEffort() {
        FraudRiskAssessment assessment =
                new FraudRiskAssessment(true, 70, FraudRiskAssessment.Level.HIGH,
                        List.of("late_night_hour"));
        service.recordReviewFlag(5L, 10L, assessment, "1.2.3.4", "fp-123");
        verify(fraudEventRepository).save(any(com.bhukkad.entity.FraudEvent.class));
    }
}

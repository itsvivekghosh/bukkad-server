package com.bhukkad.churn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChurnPredictionServiceTest {

    @Mock
    private ChurnQueryService queryService;
    @Mock
    private ChurnScoreRepository churnScoreRepository;
    @Mock
    private com.bhukkad.audit.AuditService auditService;

    private ChurnProperties properties;
    private ChurnPredictionService service;

    @BeforeEach
    void setUp() {
        properties = new ChurnProperties();
        service = new ChurnPredictionService(properties, queryService, churnScoreRepository, auditService);
        lenient().when(churnScoreRepository.findByUserIdIn(anyList())).thenReturn(List.of());
        lenient().when(churnScoreRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Object[] aggregateRow(long customerId, LocalDateTime lastOrder, long total,
                                  long recent, long prev, long cancelled, long couponOrders) {
        return new Object[]{customerId, lastOrder, total, recent, prev, cancelled, couponOrders};
    }

    @Test
    void scoreCustomer_marksLapsedCustomerHighRiskAndTriggersOutreach() {
        // Last order 90 days ago, nothing recent, previously ordering regularly.
        when(queryService.customerOrderAggregates(List.of(1L))).thenReturn(
                List.<Object[]>of(aggregateRow(1L, LocalDateTime.now().minusDays(90), 12, 0, 6, 1, 2)));

        ChurnScore score = service.scoreCustomer(1L);

        assertTrue(score.getScore() >= properties.getRetentionThreshold(),
                "expected HIGH risk, got " + score.getScore());
        assertEquals(ChurnScore.RiskLevel.HIGH, score.getRiskLevel());
        assertTrue(score.getFactors().contains("days_inactive=90"));
        verify(auditService).recordEvent(org.mockito.ArgumentMatchers.eq("RETENTION_OUTREACH"),
                org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("we_miss_you_campaign"),
                org.mockito.ArgumentMatchers.eq(1L));
        assertTrue(score.isRetentionActionTaken());
    }

    @Test
    void scoreCustomer_keepsActiveFrequentBuyerLowRisk() {
        when(queryService.customerOrderAggregates(List.of(2L))).thenReturn(
                List.<Object[]>of(aggregateRow(2L, LocalDateTime.now().minusDays(2), 40, 10, 9, 0, 0)));

        ChurnScore score = service.scoreCustomer(2L);

        assertTrue(score.getScore() < properties.getRetentionThreshold(),
                "expected low risk, got " + score.getScore());
        assertEquals(ChurnScore.RiskLevel.LOW, score.getRiskLevel());
        assertFalse(score.isRetentionActionTaken());
    }

@Test
    void scoreCustomer_noopsWhenDisabled() {
        properties.setEnabled(false);
        service.scoreCustomers();
        verify(queryService, never()).findScorableCustomerIds(anyInt());
    }

    @Test
    void scoreCustomer_emptyFactors_returnsNull() {
        when(queryService.customerOrderAggregates(List.of(1L))).thenReturn(List.of());

        ChurnScore result = service.scoreCustomer(1L);

        assertNull(result);
    }

    @Test
    void scoreCustomer_neverPurchased_returnsLowRisk() {
        when(queryService.customerOrderAggregates(List.of(1L)))
                .thenReturn(List.<Object[]>of(new Object[]{1L, app(), 0L, 0L, 0L, 0L, 0L}));

        ChurnScore result = service.scoreCustomer(1L);

        assertNotNull(result);
        assertEquals(ChurnScore.RiskLevel.LOW, result.getRiskLevel());
    }

    @Test
    void scoreCustomer_nullLastOrder_usesRecencyDecayDays() {
        when(queryService.customerOrderAggregates(List.of(1L)))
                .thenReturn(List.<Object[]>of(new Object[]{1L, null, 10L, 5L, 3L, 0L, 0L}));

        ChurnScore result = service.scoreCustomer(1L);

        assertNotNull(result);
        assertTrue(result.getScore() > 0);
    }

    @Test
    void scoreCustomer_highRisk_triggersRetention() {
        when(queryService.customerOrderAggregates(List.of(1L)))
                .thenReturn(List.<Object[]>of(new Object[]{1L, LocalDateTime.now().minusDays(90), 10L, 0L, 8L, 2L, 5L}));
        when(churnScoreRepository.findByUserIdIn(any())).thenReturn(List.of());
        when(churnScoreRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(churnScoreRepository.save(any(ChurnScore.class))).thenAnswer(inv -> inv.getArgument(0));

        ChurnScore result = service.scoreCustomer(1L);

        assertNotNull(result);
        assertEquals(ChurnScore.RiskLevel.HIGH, result.getRiskLevel());
        verify(auditService).recordEvent(eq("RETENTION_OUTREACH"), any(), any(), any(), any(), any());
        assertTrue(result.isRetentionActionTaken());
    }

    @Test
    void scoreCustomer_mediumRisk_doesNotTriggerRetention() {
        when(queryService.customerOrderAggregates(List.of(1L)))
                .thenReturn(List.<Object[]>of(new Object[]{1L, LocalDateTime.now().minusDays(30), 10L, 2L, 5L, 0L, 0L}));
        when(churnScoreRepository.findByUserIdIn(any())).thenReturn(List.of());
        when(churnScoreRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ChurnScore result = service.scoreCustomer(1L);

        assertNotNull(result);
        assertEquals(ChurnScore.RiskLevel.MEDIUM, result.getRiskLevel());
        verify(auditService, never()).recordEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void scoreCustomer_highRiskWithoutPreviousAction_triggersOutreach() {
        when(queryService.customerOrderAggregates(List.of(1L)))
                .thenReturn(List.<Object[]>of(new Object[]{1L, LocalDateTime.now().minusDays(90), 10L, 0L, 8L, 2L, 5L}));
        when(churnScoreRepository.findByUserIdIn(any())).thenReturn(List.of());
        when(churnScoreRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(churnScoreRepository.save(any(ChurnScore.class))).thenAnswer(inv -> inv.getArgument(0));

        service.scoreCustomer(1L);

        verify(auditService).recordEvent(eq("RETENTION_OUTREACH"), any(), any(), any(), any(), any());
    }

    private LocalDateTime app() {
        return LocalDateTime.now();
    }
}

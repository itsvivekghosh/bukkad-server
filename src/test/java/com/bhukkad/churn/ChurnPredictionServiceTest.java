package com.bhukkad.churn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
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
    void scoreCustomers_noopsWhenDisabled() {
        properties.setEnabled(false);
        service.scoreCustomers();
        verify(queryService, never()).findScorableCustomerIds(org.mockito.ArgumentMatchers.anyInt());
    }
}

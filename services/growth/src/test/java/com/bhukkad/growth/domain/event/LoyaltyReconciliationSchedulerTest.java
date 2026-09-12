package com.bhukkad.growth.domain.event;

import com.bhukkad.growth.domain.service.LoyaltyReconciliationService;
import com.bhukkad.growth.domain.service.LoyaltyReconciliationService.ReconciliationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The nightly tick must never propagate failures to the scheduler thread. */
@ExtendWith(MockitoExtension.class)
class LoyaltyReconciliationSchedulerTest {

    @Mock private LoyaltyReconciliationService reconciliationService;

    @InjectMocks private LoyaltyReconciliationScheduler scheduler;

    @Test
    void reconcileNightly_reportsAndSwallowsSuccess() {
        when(reconciliationService.reconcile())
                .thenReturn(new ReconciliationReport(3, 2, 45L));

        scheduler.reconcileNightly();

        verify(reconciliationService).reconcile();
    }

    @Test
    void reconcileNightly_failure_isSwallowed() {
        doThrow(new IllegalStateException("db down"))
                .when(reconciliationService).reconcile();

        scheduler.reconcileNightly();

        verify(reconciliationService).reconcile();
    }
}

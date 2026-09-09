package com.bhukkad.payment.settlement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettlementAutomationSchedulerTest {

    @Mock private SettlementAutomationService service;

    @Test
    void scheduledSettle_delegatesToday() {
        new SettlementAutomationScheduler(service).scheduledSettle();
        verify(service).settleFor(any(LocalDate.class));
    }

    @Test
    void scheduledSettle_serviceFailure_isSwallowedToKeepSchedulerAlive() {
        doThrow(new RuntimeException("db down")).when(service).settleFor(any(LocalDate.class));

        // A failed tick must not propagate: Spring's scheduler cancels the
        // fixed/cron task on uncaught exceptions.
        assertThatCode(() -> new SettlementAutomationScheduler(service).scheduledSettle())
                .doesNotThrowAnyException();
    }
}

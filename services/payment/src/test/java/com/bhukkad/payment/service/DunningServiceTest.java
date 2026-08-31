package com.bhukkad.payment.service;

import com.bhukkad.payment.domain.DunningRun;
import com.bhukkad.payment.domain.DunningRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DunningServiceTest {

    @Mock private DunningRunRepository dunningRepository;
    @InjectMocks private DunningService service;

    @Test
    void scheduleRetry_createsScheduledRun() {
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(1);
        DunningRun run = new DunningRun();
        run.setId(1L);
        when(dunningRepository.save(org.mockito.ArgumentMatchers.any(DunningRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DunningRun result = service.scheduleRetry(1L, 2, scheduledAt);

        assertThat(result.getPaymentId()).isEqualTo(1L);
        assertThat(result.getAttempt()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo("SCHEDULED");
        assertThat(result.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    void pending_returnsScheduledRuns() {
        DunningRun run = new DunningRun();
        run.setStatus("SCHEDULED");
        when(dunningRepository.findByStatus("SCHEDULED")).thenReturn(List.of(run));

        assertThat(service.pending()).containsExactly(run);
        verify(dunningRepository).findByStatus("SCHEDULED");
    }
}

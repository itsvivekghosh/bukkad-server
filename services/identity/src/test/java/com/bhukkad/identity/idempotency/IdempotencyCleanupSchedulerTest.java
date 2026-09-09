package com.bhukkad.identity.idempotency;

import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyCleanupSchedulerTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @InjectMocks
    private IdempotencyCleanupScheduler scheduler;

    @Test
    void purgeExpiredRecords_deletesExpired() {
        when(idempotencyRecordRepository.deleteByExpiresAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenReturn(7);

        scheduler.purgeExpiredRecords();

        verify(idempotencyRecordRepository).deleteByExpiresAtBefore(org.mockito.ArgumentMatchers.any());
    }
}

package com.bhukkad.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyCleanupSchedulerTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Test
    void purgeExpiredRecords_deletesExpiredAndLogs() {
        when(idempotencyRecordRepository.deleteByExpiresAtBefore(any(LocalDateTime.class))).thenReturn(5);

        new IdempotencyCleanupScheduler(idempotencyRecordRepository).purgeExpiredRecords();

        verify(idempotencyRecordRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
    }

    @Test
    void purgeExpiredRecords_zeroRemoved_noOp() {
        when(idempotencyRecordRepository.deleteByExpiresAtBefore(any(LocalDateTime.class))).thenReturn(0);

        new IdempotencyCleanupScheduler(idempotencyRecordRepository).purgeExpiredRecords();

        verify(idempotencyRecordRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
    }
}

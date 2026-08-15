package com.bhukkad.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupScheduler {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpiredRecords() {
        int removed = idempotencyRecordRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (removed > 0) {
            log.info("Purged {} expired idempotency records", removed);
        }
    }
}

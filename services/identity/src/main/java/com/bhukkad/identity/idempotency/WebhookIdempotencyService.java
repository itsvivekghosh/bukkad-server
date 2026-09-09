package com.bhukkad.identity.idempotency;

import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIdempotencyService {

    private static final Duration WEBHOOK_TTL = Duration.ofHours(48);

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(String eventId) {
        if (!StringUtils.hasText(eventId)) {
            return false;
        }
        return idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                        IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK, eventId)
                .isPresent();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessed(String eventId) {
        if (!StringUtils.hasText(eventId)) {
            return true;
        }
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(eventId);
        record.setScope(IdempotencyRecord.IdempotencyScope.RAZORPAY_WEBHOOK);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setExpiresAt(LocalDateTime.now().plus(WEBHOOK_TTL));
        idempotencyRecordRepository.saveAndFlush(record);
        return true;
    }
}

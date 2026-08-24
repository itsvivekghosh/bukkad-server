package com.bhukkad.idempotency;

import com.bhukkad.entity.Payment;
import com.bhukkad.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * DB-backed idempotency for payment processing. Mirrors
 * {@link OrderIdempotencyService} so payment retries survive a Redis loss
 * (the Redis cache in {@link IdempotencyService} is only a fast path).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentIdempotencyService {

    private static final Duration PAYMENT_TTL = Duration.ofHours(24);
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final String PAYMENT_LOCK_PREFIX = "payment:";

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public Optional<Payment> findCompletedPayment(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return Optional.empty();
        }

        Optional<Payment> cached = idempotencyService.getPaymentResult(idempotencyKey, Payment.class);
        if (cached.isPresent()) {
            return cached;
        }

        return idempotencyRecordRepository
                .findByScopeAndIdempotencyKey(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, idempotencyKey)
                .filter(record -> record.getStatus() == IdempotencyRecord.IdempotencyStatus.COMPLETED)
                .map(record -> deserialize(record.getResponsePayload()));
    }

    @Transactional
    public void beginPaymentProcess(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }

        // Atomic cross-instance first gate: SETNX in Redis guarantees only one
        // pod starts processing a given idempotency key. Best-effort — if Redis
        // is unavailable the DB unique constraint below still guards.
        if (!tryAcquireLock(idempotencyKey)) {
            throw new BusinessException("Duplicate payment request is already being processed");
        }

        try {
            Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                    IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, idempotencyKey);

            if (existing.isPresent()) {
                IdempotencyRecord record = existing.get();
                if (record.getStatus() == IdempotencyRecord.IdempotencyStatus.COMPLETED) {
                    return;
                }
                if (record.getStatus() == IdempotencyRecord.IdempotencyStatus.IN_PROGRESS) {
                    throw new BusinessException("Duplicate payment request is already being processed");
                }
                record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
                record.setExpiresAt(LocalDateTime.now().plus(PAYMENT_TTL));
                idempotencyRecordRepository.save(record);
                return;
            }

            IdempotencyRecord record = new IdempotencyRecord();
            record.setIdempotencyKey(idempotencyKey);
            record.setScope(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS);
            record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
            record.setExpiresAt(LocalDateTime.now().plus(PAYMENT_TTL));

            try {
                idempotencyRecordRepository.save(record);
            } catch (DataIntegrityViolationException ex) {
                throw new BusinessException("Duplicate payment request is already being processed");
            }
        } finally {
            // The lock is released immediately after the DB guard is set; the
            // IN_PROGRESS DB row (with its expiresAt) is the durable guard that
            // survives Redis loss and crash recovery.
            releaseLock(idempotencyKey);
        }
    }

    @Transactional
    public void completePaymentProcess(String idempotencyKey, Payment payment) {
        if (!StringUtils.hasText(idempotencyKey) || payment == null) {
            return;
        }

        String payload = serialize(payment);
        idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                        IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, idempotencyKey)
                .ifPresent(record -> {
                    record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
                    record.setResponsePayload(payload);
                    idempotencyRecordRepository.save(record);
                });

        idempotencyService.storePaymentResult(idempotencyKey, payment, PAYMENT_TTL);
    }

    @Transactional
    public void failPaymentProcess(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                        IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, idempotencyKey)
                .ifPresent(record -> {
                    record.setStatus(IdempotencyRecord.IdempotencyStatus.FAILED);
                    idempotencyRecordRepository.save(record);
                });
    }

    /**
     * Best-effort Redis SETNX lock. On Redis failure we fail open: the DB
     * unique constraint remains the authoritative duplicate guard.
     */
    private boolean tryAcquireLock(String idempotencyKey) {
        try {
            return idempotencyService.tryAcquireLock(PAYMENT_LOCK_PREFIX, idempotencyKey, LOCK_TTL);
        } catch (Exception ex) {
            log.warn("Redis idempotency lock unavailable, falling back to DB guard | key={} | error={}",
                    idempotencyKey, ex.getMessage());
            return true;
        }
    }

    private void releaseLock(String idempotencyKey) {
        try {
            idempotencyService.releaseLock(PAYMENT_LOCK_PREFIX, idempotencyKey);
        } catch (Exception ex) {
            // The lock has a TTL, so a failed delete is harmless.
            log.debug("Failed to release idempotency lock | key={} | error={}",
                    idempotencyKey, ex.getMessage());
        }
    }

    private Payment deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, Payment.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize payment idempotency payload: {}", e.getMessage());
            return null;
        }
    }

    private String serialize(Payment payment) {
        try {
            return objectMapper.writeValueAsString(payment);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize payment response for idempotency", e);
        }
    }
}

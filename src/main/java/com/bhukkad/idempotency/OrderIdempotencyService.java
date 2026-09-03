package com.bhukkad.idempotency;

import com.bhukkad.dto.response.BatchOrderResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderIdempotencyService {

    private static final Duration ORDER_TTL = Duration.ofHours(24);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    // ==================== Single order ====================

    public Optional<OrderResponse> findCompletedResponse(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return Optional.empty();
        }

        Optional<OrderResponse> cached = idempotencyService.getOrderResult(idempotencyKey, OrderResponse.class);
        if (cached.isPresent()) {
            return cached;
        }

        return findCompletedRecord(idempotencyKey, IdempotencyRecord.IdempotencyScope.ORDER_CREATE)
                .map(IdempotencyRecord::getResponsePayload)
                .map(this::deserializeOrderResponse);
    }

    @Transactional
    public void beginOrderCreate(String idempotencyKey, Long customerId) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        begin(idempotencyKey, customerId, IdempotencyRecord.IdempotencyScope.ORDER_CREATE);
    }

    @Transactional
    public void completeOrderCreate(String idempotencyKey, OrderResponse response) {
        if (!StringUtils.hasText(idempotencyKey) || response == null) {
            return;
        }

        String payload = serialize(response);
        complete(idempotencyKey, payload, IdempotencyRecord.IdempotencyScope.ORDER_CREATE);

        idempotencyService.storeOrderResult(idempotencyKey, response, ORDER_TTL);
    }

    @Transactional
    public void failOrderCreate(String idempotencyKey) {
        fail(idempotencyKey, IdempotencyRecord.IdempotencyScope.ORDER_CREATE);
    }

    // ==================== Batch order ====================

    /**
     * Returns the stored {@link BatchOrderResponse} for a completed batch key,
     * or empty when the key is unknown, failed, or still in progress.
     */
    public Optional<BatchOrderResponse> findCompletedBatchResponse(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return Optional.empty();
        }
        return findCompletedRecord(idempotencyKey, IdempotencyRecord.IdempotencyScope.BATCH_ORDER_CREATE)
                .map(IdempotencyRecord::getResponsePayload)
                .map(this::deserializeBatchResponse);
    }

    /**
     * Claims a batch-order idempotency key. In-flight duplicates (same key
     * concurrently) are rejected with {@link DuplicateRequestException} (409).
     */
    @Transactional
    public void beginBatchOrderCreate(String idempotencyKey, Long customerId) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        begin(idempotencyKey, customerId, IdempotencyRecord.IdempotencyScope.BATCH_ORDER_CREATE);
    }

    @Transactional
    public void completeBatchOrderCreate(String idempotencyKey, BatchOrderResponse response) {
        if (!StringUtils.hasText(idempotencyKey) || response == null) {
            return;
        }
        complete(idempotencyKey, serialize(response), IdempotencyRecord.IdempotencyScope.BATCH_ORDER_CREATE);
    }

    @Transactional
    public void failBatchOrderCreate(String idempotencyKey) {
        fail(idempotencyKey, IdempotencyRecord.IdempotencyScope.BATCH_ORDER_CREATE);
    }

    // ==================== Shared primitives ====================

    private Optional<IdempotencyRecord> findCompletedRecord(String idempotencyKey,
                                                            IdempotencyRecord.IdempotencyScope scope) {
        return idempotencyRecordRepository
                .findByScopeAndIdempotencyKey(scope, idempotencyKey)
                .filter(record -> record.getStatus() == IdempotencyRecord.IdempotencyStatus.COMPLETED);
    }

    private void begin(String idempotencyKey, Long customerId, IdempotencyRecord.IdempotencyScope scope) {
        Optional<IdempotencyRecord> existing = idempotencyRecordRepository
                .findByScopeAndIdempotencyKey(scope, idempotencyKey);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (record.getStatus() == IdempotencyRecord.IdempotencyStatus.COMPLETED) {
                return;
            }
            if (record.getStatus() == IdempotencyRecord.IdempotencyStatus.IN_PROGRESS) {
                throw new DuplicateRequestException("Duplicate order request is already being processed");
            }
            record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
            record.setOwnerId(customerId);
            record.setExpiresAt(LocalDateTime.now().plus(ORDER_TTL));
            idempotencyRecordRepository.save(record);
            return;
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(idempotencyKey);
        record.setScope(scope);
        record.setOwnerId(customerId);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
        record.setExpiresAt(LocalDateTime.now().plus(ORDER_TTL));

        try {
            idempotencyRecordRepository.save(record);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateRequestException("Duplicate order request is already being processed");
        }
    }

    private void complete(String idempotencyKey, String payload, IdempotencyRecord.IdempotencyScope scope) {
        idempotencyRecordRepository.findByScopeAndIdempotencyKey(scope, idempotencyKey)
                .ifPresent(record -> {
                    record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
                    record.setResponsePayload(payload);
                    idempotencyRecordRepository.save(record);
                });
    }

    private void fail(String idempotencyKey, IdempotencyRecord.IdempotencyScope scope) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        idempotencyRecordRepository.findByScopeAndIdempotencyKey(scope, idempotencyKey)
                .ifPresent(record -> {
                    record.setStatus(IdempotencyRecord.IdempotencyStatus.FAILED);
                    idempotencyRecordRepository.save(record);
                });
    }

    private OrderResponse deserializeOrderResponse(String payload) {
        try {
            return objectMapper.readValue(payload, OrderResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize order idempotency payload: {}", e.getMessage());
            return null;
        }
    }

    private BatchOrderResponse deserializeBatchResponse(String payload) {
        try {
            return objectMapper.readValue(payload, BatchOrderResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize batch idempotency payload: {}", e.getMessage());
            return null;
        }
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotency payload", e);
        }
    }
}

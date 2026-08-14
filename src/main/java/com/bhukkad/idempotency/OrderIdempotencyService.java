package com.bhukkad.idempotency;

import com.bhukkad.dto.response.OrderResponse;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderIdempotencyService {

    private static final Duration ORDER_TTL = Duration.ofHours(24);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public Optional<OrderResponse> findCompletedResponse(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return Optional.empty();
        }

        Optional<OrderResponse> cached = idempotencyService.getOrderResult(idempotencyKey, OrderResponse.class);
        if (cached.isPresent()) {
            return cached;
        }

        return idempotencyRecordRepository
                .findByScopeAndIdempotencyKey(IdempotencyRecord.IdempotencyScope.ORDER_CREATE, idempotencyKey)
                .filter(record -> record.getStatus() == IdempotencyRecord.IdempotencyStatus.COMPLETED)
                .map(record -> deserialize(record.getResponsePayload()));
    }

    @Transactional
    public void beginOrderCreate(String idempotencyKey, Long customerId) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, idempotencyKey);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (record.getStatus() == IdempotencyRecord.IdempotencyStatus.COMPLETED) {
                return;
            }
            if (record.getStatus() == IdempotencyRecord.IdempotencyStatus.IN_PROGRESS) {
                throw new BusinessException("Duplicate order request is already being processed");
            }
            record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
            record.setOwnerId(customerId);
            record.setExpiresAt(LocalDateTime.now().plus(ORDER_TTL));
            idempotencyRecordRepository.save(record);
            return;
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(idempotencyKey);
        record.setScope(IdempotencyRecord.IdempotencyScope.ORDER_CREATE);
        record.setOwnerId(customerId);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
        record.setExpiresAt(LocalDateTime.now().plus(ORDER_TTL));

        try {
            idempotencyRecordRepository.save(record);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("Duplicate order request is already being processed");
        }
    }

    @Transactional
    public void completeOrderCreate(String idempotencyKey, OrderResponse response) {
        if (!StringUtils.hasText(idempotencyKey) || response == null) {
            return;
        }

        String payload = serialize(response);
        idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                        IdempotencyRecord.IdempotencyScope.ORDER_CREATE, idempotencyKey)
                .ifPresent(record -> {
                    record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
                    record.setResponsePayload(payload);
                    idempotencyRecordRepository.save(record);
                });

        idempotencyService.storeOrderResult(idempotencyKey, response, ORDER_TTL);
    }

    @Transactional
    public void failOrderCreate(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                        IdempotencyRecord.IdempotencyScope.ORDER_CREATE, idempotencyKey)
                .ifPresent(record -> {
                    record.setStatus(IdempotencyRecord.IdempotencyStatus.FAILED);
                    idempotencyRecordRepository.save(record);
                });
    }

    private OrderResponse deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, OrderResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize idempotency payload: {}", e.getMessage());
            return null;
        }
    }

    private String serialize(OrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize order response for idempotency", e);
        }
    }
}

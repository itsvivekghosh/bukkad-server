package com.bhukkad.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String ORDER_PREFIX = "idempotency:order:";
    private static final String PAYMENT_PREFIX = "idempotency:payment:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public <T> Optional<T> getOrderResult(String idempotencyKey, Class<T> type) {
        return get(ORDER_PREFIX, idempotencyKey, type);
    }

    public void storeOrderResult(String idempotencyKey, Object result, Duration ttl) {
        store(ORDER_PREFIX, idempotencyKey, result, ttl);
    }

    public <T> Optional<T> getPaymentResult(String idempotencyKey, Class<T> type) {
        return get(PAYMENT_PREFIX, idempotencyKey, type);
    }

    public void storePaymentResult(String idempotencyKey, Object result, Duration ttl) {
        store(PAYMENT_PREFIX, idempotencyKey, result, ttl);
    }

    private <T> Optional<T> get(String prefix, String idempotencyKey, Class<T> type) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return Optional.empty();
        }
        String payload = stringRedisTemplate.opsForValue().get(prefix + idempotencyKey);
        if (!StringUtils.hasText(payload)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, type));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private void store(String prefix, String idempotencyKey, Object result, Duration ttl) {
        if (!StringUtils.hasText(idempotencyKey) || result == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    prefix + idempotencyKey,
                    objectMapper.writeValueAsString(result),
                    ttl.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException ignored) {
            // skip caching on serialization failure
        }
    }
}

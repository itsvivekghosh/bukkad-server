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

    /**
     * Atomically acquires a Redis-backed lock keyed by the idempotency key.
     * Uses {@code SETNX} so only one caller across all application instances
     * wins. The lock auto-expires after the given TTL / timeout.
     *
     * @param prefix  key prefix for the scope (e.g. payment or order)
     * @param idempotencyKey the unique idempotency key
     * @param ttl     lock TTL; should be long enough for the operation, short
     *                enough to avoid blocking retries after a crash
     * @return {@code true} if the lock was acquired, {@code false} otherwise
     */
    public boolean tryAcquireLock(String prefix, String idempotencyKey, Duration ttl) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return false;
        }
        String key = "lock:" + prefix + idempotencyKey;
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                key, "locked", ttl.toMillis(), TimeUnit.MILLISECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Releases a Redis lock acquired via {@link #tryAcquireLock}. Safe to call
     * even if the lock has already expired (no-op).
     */
    public void releaseLock(String prefix, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        stringRedisTemplate.delete("lock:" + prefix + idempotencyKey);
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

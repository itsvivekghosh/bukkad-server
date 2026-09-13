package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecord.IdempotencyScope;
import com.bhukkad.common.idempotency.IdempotencyRecord.IdempotencyStatus;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Replay guard for the sync legacy order-create surface
 * ({@code POST /api/v1/orders/customer/create}) when an {@code Idempotency-Key}
 * header is present. Same convention as the platform batch flows: the
 * {@code idempotency_records} (scope {@code ORDER_CREATE}) unique
 * {@code (scope, key)} index is the first-write-wins claim, the stored
 * {@code response_payload} is the replay envelope
 * ({@code {"requestHash": ..., "httpStatus": ..., "body": ...}}), and a
 * same-key/different-payload reuse surfaces as a 409
 * ({@link DuplicateRequestException}) — never a second saga execution.
 *
 * <p>All claim/complete/fail transitions run in their own committed
 * transaction ({@link Propagation#REQUIRES_NEW}) so a claim is visible to
 * concurrent replays even while the (much slower) order saga is still open,
 * and a saga rollback cannot undo the dedupe bookkeeping.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateIdempotencyService {

    private static final IdempotencyScope SCOPE = IdempotencyScope.ORDER_CREATE;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final IdempotencyRecordRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    /** Outcome of a claim: either run the saga ({@code fresh}) or replay. */
    public record Claim(boolean fresh, int httpStatus, OrderResponse replay) {
        public static Claim ofFresh() {
            return new Claim(true, 0, null);
        }

        public static Claim ofReplay(int status, OrderResponse body) {
            return new Claim(false, status, body);
        }
    }

    /**
     * Claims {@code (ORDER_CREATE, key)} for the caller. Contract:
     * <ul>
     *   <li>first call with the key → {@link Claim#fresh()} (row inserted IN_PROGRESS);</li>
     *   <li>same key + same request, already completed → replay of the stored
     *       response (same status, same body);</li>
     *   <li>same key + different request, or a request still in progress, or a
     *       key owned by another customer → 409 via
     *       {@link DuplicateRequestException} (and never leaks the other
     *       request's payload).</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(Long customerId, String idempotencyKey, CreateOrderRequest request) {
        String requestHash = hashOf(customerId, request);
        int inserted = idempotencyRepository.insertIfAbsent(
                idempotencyKey, SCOPE.name(), customerId,
                IdempotencyStatus.IN_PROGRESS.name(),
                claimPayload(requestHash),
                LocalDateTime.now().plus(IDEMPOTENCY_TTL));
        if (inserted == 1) {
            return Claim.ofFresh();
        }
        IdempotencyRecord existing = idempotencyRepository
                .findByScopeAndIdempotencyKey(SCOPE, idempotencyKey)
                .orElseThrow(() -> new DuplicateRequestException(
                        "Idempotency-Key " + idempotencyKey + " is locked by an in-flight request"));
        // Keys are client-supplied: a guessable key must never replay another
        // customer's stored order (nor tell them it exists) — same 409 the
        // payload conflict uses.
        if (!Objects.equals(existing.getOwnerId(), customerId)) {
            throw new DuplicateRequestException(
                    "Idempotency-Key already in use: " + idempotencyKey);
        }
        JsonNode envelope = parsePayload(existing);
        String storedHash = envelope.path("requestHash").asText(null);
        if (storedHash == null || !storedHash.equals(requestHash)) {
            throw new DuplicateRequestException(
                    "Idempotency-Key reused with a different request payload");
        }
        if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
            if (!envelope.hasNonNull("body")) {
                throw new DuplicateRequestException(
                        "Stored idempotent response is unreadable for key " + idempotencyKey);
            }
            try {
                // Typed (de)serialization (not JsonNode trees): field-targeted
                // BigDecimal binding keeps the raw numeric text ("320.00"),
                // so the replayed JSON is byte-identical to the fresh response.
                StoredEnvelope stored = objectMapper.readValue(
                        existing.getResponsePayload(), StoredEnvelope.class);
                return Claim.ofReplay(
                        stored.httpStatus() == null ? 200 : stored.httpStatus(), stored.body());
            } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
                log.warn("IDEMPOTENCY_REPLAY_UNREADABLE | key={} | {}", idempotencyKey,
                        unreadable.getMessage());
                throw new DuplicateRequestException(
                        "Stored idempotent response is unreadable for key " + idempotencyKey);
            }
        }
        if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new DuplicateRequestException(
                    "A request with this Idempotency-Key is still in progress: " + idempotencyKey);
        }
        // FAILED + same payload: the first attempt created nothing, so re-run
        // the saga under the same claim (re-mark IN_PROGRESS, re-extend expiry).
        existing.setStatus(IdempotencyStatus.IN_PROGRESS);
        existing.setExpiresAt(LocalDateTime.now().plus(IDEMPOTENCY_TTL));
        idempotencyRepository.saveAndFlush(existing);
        return Claim.ofFresh();
    }

    /** Stores the successful response under the claim so replays return it verbatim. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long customerId, String idempotencyKey, CreateOrderRequest request,
                         int httpStatus, OrderResponse body) {
        IdempotencyRecord record = requireOwnedClaim(customerId, idempotencyKey);
        if (record == null) {
            return;
        }
        try {
            record.setStatus(IdempotencyStatus.COMPLETED);
            record.setResponsePayload(objectMapper.writeValueAsString(
                    new StoredEnvelope(hashOf(customerId, request), httpStatus, body)));
            record.setExpiresAt(LocalDateTime.now().plus(IDEMPOTENCY_TTL));
            idempotencyRepository.saveAndFlush(record);
        } catch (com.fasterxml.jackson.core.JsonProcessingException serializationFailure) {
            throw new IllegalStateException(
                    "Failed to serialize idempotent order response", serializationFailure);
        }
    }

    /** Persisted replay envelope: {@code {"requestHash":..,"httpStatus":..,"body":..}}. */
    record StoredEnvelope(String requestHash, Integer httpStatus, OrderResponse body) {
    }

    /**
     * Marks the claim FAILED so a client retry (same payload) re-runs the saga
     * instead of 409-ing forever on a request that created nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long customerId, String idempotencyKey) {
        IdempotencyRecord record = requireOwnedClaim(customerId, idempotencyKey);
        if (record == null || record.getStatus() == IdempotencyStatus.COMPLETED) {
            return; // never downgrade a completed outcome
        }
        record.setStatus(IdempotencyStatus.FAILED);
        idempotencyRepository.saveAndFlush(record);
    }

    private IdempotencyRecord requireOwnedClaim(Long customerId, String idempotencyKey) {
        return idempotencyRepository.findByScopeAndIdempotencyKey(SCOPE, idempotencyKey)
                .filter(r -> Objects.equals(r.getOwnerId(), customerId))
                .orElse(null);
    }

    private JsonNode parsePayload(IdempotencyRecord record) {
        try {
            if (record.getResponsePayload() == null || record.getResponsePayload().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(record.getResponsePayload());
        } catch (com.fasterxml.jackson.core.JsonProcessingException
                 | IllegalArgumentException malformed) {
            // Defensive: a legacy/hand-edited payload row must not 500 a claim
            // check — it degrades to the "unreadable" conflict path instead.
            return objectMapper.createObjectNode();
        }
    }

    private String claimPayload(String requestHash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestHash", requestHash);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Failed to serialize idempotency claim", serializationFailure);
        }
    }

    /**
     * Canonical SHA-256 over the fields this endpoint acts on (the authenticated
     * subject, the restaurant and the explicit item list — the omitted-items
     * cart snapshot is a server side effect, so both identical bodies hash the
     * same way exactly when they should).
     */
    String hashOf(Long customerId, CreateOrderRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("customerId", customerId);
        canonical.put("restaurantId", request.restaurantId());
        canonical.put("items", request.items() == null ? "<ACTIVE_CART>" : request.items());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandatory on every JDK
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonicalize idempotency payload", e);
        }
    }
}

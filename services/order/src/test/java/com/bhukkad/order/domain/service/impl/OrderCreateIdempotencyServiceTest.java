package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecord.IdempotencyScope;
import com.bhukkad.common.idempotency.IdempotencyRecord.IdempotencyStatus;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.request.OrderItemRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * (scope=ORDER_CREATE, key) claim semantics for the sync legacy create:
 * first-write-wins claim, same-payload replay of the stored envelope,
 * different-payload / foreign-owner / in-progress conflicts as 409, FAILED +
 * same payload re-runs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCreateIdempotencyServiceTest {

    @Mock private IdempotencyRecordRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderCreateIdempotencyService service;

    private static final Long CUSTOMER = 7L;
    private static final String KEY = "key-1";

    private static CreateOrderRequest request() {
        return new CreateOrderRequest(null, 100L, List.of(
                new OrderItemRequest(42L, "Butter Chicken", new BigDecimal("320.00"), 1)));
    }

    private static OrderResponse order() {
        return new OrderResponse(42L, 7L, 100L, "PLACED", new BigDecimal("320.00"), List.of());
    }

    @BeforeEach
    void setUp() {
        service = new OrderCreateIdempotencyService(repository, objectMapper);
    }

    private IdempotencyRecord record(IdempotencyStatus status, String payload) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(KEY);
        record.setScope(IdempotencyScope.ORDER_CREATE);
        record.setOwnerId(CUSTOMER);
        record.setStatus(status);
        record.setResponsePayload(payload);
        record.setExpiresAt(LocalDateTime.now().plusHours(24));
        return record;
    }

    private String completedEnvelope(String hash) {
        return "{\"requestHash\":\"" + hash + "\",\"httpStatus\":200,\"body\":{\"id\":42,"
                + "\"customerId\":7,\"restaurantId\":100,\"status\":\"PLACED\","
                + "\"totalAmount\":320.00,\"items\":[]}}";
    }

    @Test
    void firstClaim_insertsInProgressAndRunsFresh() {
        when(repository.insertIfAbsent(anyString(), anyString(), anyLong(), anyString(),
                anyString(), any(LocalDateTime.class))).thenReturn(1);

        OrderCreateIdempotencyService.Claim claim = service.claim(CUSTOMER, KEY, request());

        assertThat(claim.fresh()).isTrue();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIfAbsent(eq(KEY), eq("ORDER_CREATE"), eq(CUSTOMER),
                eq("IN_PROGRESS"), payload.capture(), any(LocalDateTime.class));
        assertThat(payload.getValue()).contains(service.hashOf(CUSTOMER, request()));
    }

    @Test
    void completedSamePayload_replaysStoredResponse() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.of(record(IdempotencyStatus.COMPLETED,
                        completedEnvelope(service.hashOf(CUSTOMER, request())))));

        OrderCreateIdempotencyService.Claim claim = service.claim(CUSTOMER, KEY, request());

        assertThat(claim.fresh()).isFalse();
        assertThat(claim.httpStatus()).isEqualTo(200);
        assertThat(claim.replay().id()).isEqualTo(42L);
        assertThat(claim.replay().status()).isEqualTo("PLACED");
        assertThat(claim.replay().totalAmount()).isEqualByComparingTo("320.00");
    }

    @Test
    void completedDifferentPayload_conflicts409() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.of(record(IdempotencyStatus.COMPLETED,
                        completedEnvelope("other-hash"))));

        assertThatThrownBy(() -> service.claim(CUSTOMER, KEY, request()))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("different request payload");
    }

    @Test
    void foreignOwnerKey_conflictsWithoutReplay() {
        IdempotencyRecord foreign = record(IdempotencyStatus.COMPLETED,
                completedEnvelope(service.hashOf(999L, request())));
        foreign.setOwnerId(999L);
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.of(foreign));

        // Never replay (nor confirm) another customer's stored order.
        assertThatThrownBy(() -> service.claim(CUSTOMER, KEY, request()))
                .isInstanceOf(DuplicateRequestException.class);
    }

    @Test
    void inProgressSameKey_conflicts() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.of(record(IdempotencyStatus.IN_PROGRESS,
                        "{\"requestHash\":\"" + service.hashOf(CUSTOMER, request()) + "\"}")));

        assertThatThrownBy(() -> service.claim(CUSTOMER, KEY, request()))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("in progress");
    }

    @Test
    void failedSamePayload_reRunsFresh_underTheSameClaim() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.of(record(IdempotencyStatus.FAILED,
                        "{\"requestHash\":\"" + service.hashOf(CUSTOMER, request()) + "\"}")));

        OrderCreateIdempotencyService.Claim claim = service.claim(CUSTOMER, KEY, request());

        assertThat(claim.fresh()).isTrue();
        ArgumentCaptor<IdempotencyRecord> requeued = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repository).saveAndFlush(requeued.capture());
        assertThat(requeued.getValue().getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void lostClaimRace_conflicts() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim(CUSTOMER, KEY, request()))
                .isInstanceOf(DuplicateRequestException.class);
    }

    @Test
    void complete_storesEnvelopeForReplay() {
        IdempotencyRecord claim = record(IdempotencyStatus.IN_PROGRESS,
                "{\"requestHash\":\"" + service.hashOf(CUSTOMER, request()) + "\"}");
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.of(claim));

        service.complete(CUSTOMER, KEY, request(), 200, order());

        ArgumentCaptor<IdempotencyRecord> saved = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(saved.getValue().getResponsePayload())
                .contains("\"httpStatus\":200")
                .contains("\"id\":42");
    }

    @Test
    void freshThenReplay_storesAndReplaysTheIdenticalBodyBytes() throws Exception {
        // Simulate the table with one row: fresh claim → saga → complete → replay.
        final IdempotencyRecord[] row = new IdempotencyRecord[1];
        when(repository.insertIfAbsent(eq(KEY), anyString(), anyLong(), anyString(),
                anyString(), any(LocalDateTime.class))).thenAnswer(invocation -> {
            if (row[0] != null) {
                return 0;
            }
            IdempotencyRecord created = new IdempotencyRecord();
            created.setIdempotencyKey(KEY);
            created.setScope(IdempotencyScope.ORDER_CREATE);
            created.setOwnerId(CUSTOMER);
            created.setStatus(IdempotencyStatus.IN_PROGRESS);
            created.setResponsePayload(invocation.getArgument(4));
            created.setExpiresAt(LocalDateTime.now().plusHours(24));
            row[0] = created;
            return 1;
        });
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenAnswer(invocation -> Optional.ofNullable(row[0]));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.claim(CUSTOMER, KEY, request()).fresh()).isTrue();
        service.complete(CUSTOMER, KEY, request(), 200, order());

        OrderCreateIdempotencyService.Claim replay = service.claim(CUSTOMER, KEY, request());
        assertThat(replay.fresh()).isFalse();
        assertThat(new ObjectMapper().writeValueAsString(replay.replay()))
                .isEqualTo(new ObjectMapper().writeValueAsString(order()));
    }

    @Test
    void markFailed_recordsFailure_forSamePayloadRetry() {
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.of(record(IdempotencyStatus.IN_PROGRESS, "{}")));

        service.markFailed(CUSTOMER, KEY);

        ArgumentCaptor<IdempotencyRecord> saved = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    void markFailed_neverDowngradesACompletedOutcome() {
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.of(record(IdempotencyStatus.COMPLETED, "{}")));

        service.markFailed(CUSTOMER, KEY);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void hash_isPayloadSensitiveAndCartSnapshotStable() {
        String base = service.hashOf(CUSTOMER, request());
        assertThat(base).isEqualTo(service.hashOf(CUSTOMER, request()));
        assertThat(base).isNotEqualTo(service.hashOf(8L, request()));

        String cartCheckout = service.hashOf(CUSTOMER,
                new CreateOrderRequest(null, 100L, null));
        assertThat(cartCheckout).isEqualTo(service.hashOf(CUSTOMER,
                new CreateOrderRequest(999L, 100L, null)));
        assertThat(cartCheckout).isNotEqualTo(base);
    }

    @Test
    void foreignOwnerCannotCompleteOrFailAnotherClaim() {
        when(repository.findByScopeAndIdempotencyKey(IdempotencyScope.ORDER_CREATE, KEY))
                .thenReturn(Optional.of(record(IdempotencyStatus.IN_PROGRESS, "{}")));

        service.complete(999L, KEY, request(), 200, order());
        service.markFailed(999L, KEY);

        verify(repository, never()).saveAndFlush(any());
    }
}

package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.RestaurantOrderStatRepository;
import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminCqrsEventConsumer} under the PERF-2/V-10+V-12
 * contract: atomic {@code upsertIncrement}, eventId claim via
 * {@code ADMIN_PROJECTION} idempotency records, no exception-swallowing, and
 * deliberate skip only for non-{@code OrderCreated} types.
 *
 * <p>The previous suite asserted the OLD broken behaviours (findById→+1→save
 * captures and {@code malformedPayload_doesNotThrow} — the ack-the-poison
 * bug); those expectations were rewritten fail-first.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminCqrsEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private RestaurantOrderStatRepository statRepository;
    @Mock
    private IdempotencyRecordRepository idempotencyRecords;

    private AdminCqrsEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AdminCqrsEventConsumer(statRepository, idempotencyRecords, objectMapper);
        // Fresh claim wins by default.
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), isNull(), anyString(),
                isNull(), any(LocalDateTime.class))).thenReturn(1);
    }

    @Test
    void orderCreatedEvent_claimsAndAtomicallyIncrements() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42",
                "{\"orderId\":42,\"customerId\":7,\"restaurantId\":10,\"totalAmount\":29.50}");

        consumer.onOrderEvent(objectMapper.writeValueAsString(event));

        verify(idempotencyRecords).insertIfAbsent(eq(event.eventId()),
                eq(IdempotencyRecord.IdempotencyScope.ADMIN_PROJECTION.name()),
                isNull(),
                eq(IdempotencyRecord.IdempotencyStatus.COMPLETED.name()),
                isNull(), any(LocalDateTime.class));
        // BigDecimal scale differs (Jackson double round-trip: 29.50 -> 29.5);
        // compare numerically, matching what the old save-path asserted.
        verify(statRepository).upsertIncrement(eq(10L),
                org.mockito.ArgumentMatchers.argThat(a -> new BigDecimal("29.50").compareTo(a) == 0));
        // The lost-update RMW path must be gone entirely:
        verify(statRepository, never()).findById(any());
        verify(statRepository, never()).save(any());
    }

    @Test
    void duplicateEventId_skipsProjection() throws Exception {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), isNull(), anyString(),
                isNull(), any(LocalDateTime.class))).thenReturn(0);
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{\"restaurantId\":10,\"totalAmount\":1.00}");

        consumer.onOrderEvent(objectMapper.writeValueAsString(event));

        verifyNoInteractions(statRepository);
    }

    @Test
    void nonOrderCreatedEvent_isDeliberatelySkipped() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderStatusChanged", "42", "{\"status\":\"DELIVERED\"}");

        assertThatCode(() -> consumer.onOrderEvent(objectMapper.writeValueAsString(event)))
                .doesNotThrowAnyException();
        verifyNoInteractions(statRepository);
        verifyNoInteractions(idempotencyRecords);
    }

    /** V-10: a malformed envelope must THROW (retry → DLPR → DLT), never ack quietly. */
    @Test
    void malformedPayload_throwsForDltRouting() {
        assertThatThrownBy(() -> consumer.onOrderEvent("not-json"))
                .isInstanceOf(PoisonEventException.class);
    }

    /** V-22 in the admin pair: an OrderCreated without a usable restaurant is poison. */
    @Test
    void missingRestaurantId_throwsPoison() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{\"totalAmount\":10.0}");

        assertThatThrownBy(() -> consumer.onOrderEvent(objectMapper.writeValueAsString(event)))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("restaurantId");
        // Dedupe row NOT burned -> a fixed replay can still project.
        verifyNoInteractions(idempotencyRecords);
        verify(statRepository, never()).upsertIncrement(anyLong(), any());
    }
}

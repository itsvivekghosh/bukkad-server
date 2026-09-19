package com.bhukkad.search.infrastructure.messaging;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.search.domain.service.impl.SearchSyncProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit contract for the ADR-002 search-sync consumer: type routing,
 * claim-before-project idempotency, validate-before-claim ordering for poison
 * records (V-10) and explicit skipping of irrelevant event types.
 */
@ExtendWith(MockitoExtension.class)
class SearchSyncEventConsumerTest {

    @Mock private SearchSyncProjectionService projectionService;
    @Mock private IdempotencyRecordRepository idempotencyRecords;

    private ObjectMapper mapper;
    private SearchSyncEventConsumer consumer;

    @BeforeEach
    void init() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new SearchSyncEventConsumer(projectionService, idempotencyRecords, mapper);
    }

    @Test
    void restaurantUpdated_claimsThenProjects() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);
        PlatformEventMessage event = PlatformEventMessage.of(
                "restaurant_updated", "7", "{\"id\":7,\"name\":\"Spice\"}");

        consumer.processEvent(event);

        verify(projectionService).upsertRestaurant(eq(7L), any());
        verify(idempotencyRecords).insertIfAbsent(eq(event.eventId()), eq("SEARCH_SYNC"),
                any(), eq("COMPLETED"), any(), any());
    }

    @Test
    void menuItemChangedAndDeleted_routeToProjection() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);

        consumer.processEvent(PlatformEventMessage.of(
                "menu_item_changed", "21", "{\"id\":21,\"name\":\"Butter Chicken\"}"));
        consumer.processEvent(PlatformEventMessage.of(
                "menu_item_deleted", "21", "{\"id\":21}"));

        verify(projectionService).upsertMenuItem(eq(21L), any());
        verify(projectionService).deleteMenuItem(eq(21L), any());
    }

    @Test
    void irrelevantEventType_skipsProjectionAfterClaim() {
        consumer.processEvent(PlatformEventMessage.of(
                "restaurant_created", "7", "{\"id\":7}"));

        verifyNoInteractions(projectionService);
        verify(idempotencyRecords).insertIfAbsent(anyString(), eq("SEARCH_SYNC"), any(), eq("COMPLETED"), any(), any());
    }

    @Test
    void malformedEnvelope_becomesPoisonEvent() {
        assertThatThrownBy(() -> consumer.onRestaurantEvent("{definitely-not-json"))
                .isInstanceOf(PoisonEventException.class)
                .hasMessage("Malformed search-sync envelope");
    }

    @Test
    void missingOrNonPositiveId_isPoison_AfterClaim() {
        // A DLT-parked record must stay replayable: the dedupe row is burned
        // before payload validation, matching claim-then-project ordering.
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);

        PlatformEventMessage event = new PlatformEventMessage(
                "x", "restaurant_updated", 1, Instant.now(), "x", "x", null, "{\"name\":\"no id\"}");

        assertThatThrownBy(() -> consumer.processEvent(event))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("without a usable id");
        verify(idempotencyRecords).insertIfAbsent(anyString(), eq("SEARCH_SYNC"), any(), eq("COMPLETED"), any(), any());
        verifyNoInteractions(projectionService);
    }

    @Test
    void blankEventId_isPoison() {
        PlatformEventMessage event = new PlatformEventMessage(
                "  ", "restaurant_updated", 1, Instant.now(), "7", "  ", null, "{\"id\":7}");

        assertThatThrownBy(() -> consumer.processEvent(event))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("without eventId");
    }

    @Test
    void duplicateDelivery_skipsProjection() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(0);

        consumer.processEvent(PlatformEventMessage.of(
                "menu_item_changed", "9", "{\"id\":9}"));

        verify(projectionService, never()).upsertMenuItem(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void malformedPayload_afterValidEnvelope_isPoison() {
        // Malformed payload is detected during processEvent, not envelope parsing.
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);

        String payload = "}{\"";
        PlatformEventMessage event = new PlatformEventMessage(
                "e1", "restaurant_updated", 1, Instant.now(), "e1", "e1", null, payload);

        assertThatThrownBy(() -> consumer.processEvent(event))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("Malformed search-sync payload");
    }
}

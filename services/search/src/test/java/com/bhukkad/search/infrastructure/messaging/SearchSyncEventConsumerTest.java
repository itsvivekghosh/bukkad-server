package com.bhukkad.search.infrastructure.messaging;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.search.domain.service.impl.SearchSyncProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        consumer.onRestaurantEvent(event.toJson());

        verify(projectionService).upsertRestaurant(eq(7L), any());
        verify(idempotencyRecords).insertIfAbsent(eq(event.eventId()), eq("SEARCH_SYNC"),
                any(), eq("COMPLETED"), any(), any());
    }

    @Test
    void menuItemChangedAndDeleted_routeToProjection() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);

        consumer.onRestaurantEvent(PlatformEventMessage.of(
                "menu_item_changed", "21", "{\"id\":21,\"name\":\"Butter Chicken\"}").toJson());
        consumer.onRestaurantEvent(PlatformEventMessage.of(
                "menu_item_deleted", "21", "{\"id\":21}").toJson());

        verify(projectionService).upsertMenuItem(eq(21L), any());
        verify(projectionService).deleteMenuItem(eq(21L), any());
    }

    @Test
    void irrelevantEventType_skippedWithoutClaim() {
        consumer.onRestaurantEvent(PlatformEventMessage.of(
                "restaurant_created", "7", "{\"id\":7}").toJson());

        verifyNoInteractions(projectionService);
        verifyNoInteractions(idempotencyRecords);
    }

    @Test
    void malformedEnvelope_becomesPoisonEvent() {
        assertThatThrownBy(() -> consumer.onRestaurantEvent("{definitely-not-json"))
                .isInstanceOf(PoisonEventException.class)
                .hasMessage("Malformed search-sync envelope");
    }

    @Test
    void missingOrNonPositiveId_isPoison_BEFOREClaim() {
        // A DLT-parked record must stay replayable: never burn the dedupe row
        // for an event that cannot be projected.
        String json = PlatformEventMessage.of("restaurant_updated", "x", "{\"name\":\"no id\"}")
                .toJson();

        assertThatThrownBy(() -> consumer.onRestaurantEvent(json))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("without a usable id");
        verifyNoInteractions(idempotencyRecords);
    }

    @Test
    void blankEventId_isPoison() {
        String json = "{\"eventType\":\"restaurant_updated\",\"schemaVersion\":1,\"payload\":\"{\\\"id\\\":7}\",\"eventId\":\"  \"}";

        assertThatThrownBy(() -> consumer.onRestaurantEvent(json))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("without eventId");
    }

    @Test
    void duplicateDelivery_skipsProjection() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(0);

        consumer.onRestaurantEvent(PlatformEventMessage.of(
                "menu_item_changed", "9", "{\"id\":9}").toJson());

        verify(projectionService, never()).upsertMenuItem(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void malformedPayload_afterValidEnvelope_isPoison() {
        String json = "{\"eventId\":\"e1\",\"eventType\":\"restaurant_updated\",\"schemaVersion\":1,\"payload\":\"}{\"}";

        assertThatThrownBy(() -> consumer.onRestaurantEvent(json))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("Malformed search-sync payload");
    }
}

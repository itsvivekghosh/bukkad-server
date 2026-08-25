package com.bhukkad.survey;

import com.bhukkad.event.OrderItemsSnapshotEvent;
import com.bhukkad.repository.TrendingDishRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TrendingDishMaterializerTest {

    @Mock
    private TrendingDishRepository trendingDishRepository;

    @InjectMocks
    private TrendingDishMaterializer materializer;

    private OrderItemsSnapshotEvent.Item item(Long id, String name, Integer qty) {
        return new OrderItemsSnapshotEvent.Item(id, name, qty);
    }

    private OrderItemsSnapshotEvent eventWithItems(List<OrderItemsSnapshotEvent.Item> items) {
        return new OrderItemsSnapshotEvent(100L, "ORD-100", 1L, items, LocalDateTime.now());
    }

    @Test
    void onOrderItemsSnapshot_upsertsEachItem() {
        materializer.onOrderItemsSnapshot(eventWithItems(List.of(
                item(1L, "Butter Chicken", 2),
                item(2L, "Paneer Tikka", 1))));

        verify(trendingDishRepository).upsert(eq(1L), eq(1L), eq("Butter Chicken"), eq(2L), any());
        verify(trendingDishRepository).upsert(eq(2L), eq(1L), eq("Paneer Tikka"), eq(1L), any());
    }

    @Test
    void onOrderItemsSnapshot_nullEvent_doesNothing() {
        assertDoesNotThrow(() -> materializer.onOrderItemsSnapshot(null));
        verify(trendingDishRepository, never()).upsert(anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void onOrderItemsSnapshot_emptyItems_doesNothing() {
        materializer.onOrderItemsSnapshot(eventWithItems(List.of()));
        verify(trendingDishRepository, never()).upsert(anyLong(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void onOrderItemsSnapshot_nullItem_skipped() {
        materializer.onOrderItemsSnapshot(eventWithItems(Arrays.asList(null, item(1L, "Valid", 1))));

        // Only the valid item is upserted; the null entry is skipped.
        verify(trendingDishRepository).upsert(eq(1L), eq(1L), eq("Valid"), eq(1L), any());
    }

    @Test
    void onOrderItemsSnapshot_nullItemId_skipped() {
        materializer.onOrderItemsSnapshot(eventWithItems(
                List.of(item(null, "NoId", 1), item(1L, "Valid", 1))));

        verify(trendingDishRepository).upsert(eq(1L), eq(1L), eq("Valid"), eq(1L), any());
    }

    @Test
    void onOrderItemsSnapshot_nullQuantity_defaultsToZero() {
        materializer.onOrderItemsSnapshot(eventWithItems(
                List.of(new OrderItemsSnapshotEvent.Item(1L, "Item", null))));

        verify(trendingDishRepository).upsert(eq(1L), eq(1L), eq("Item"), eq(0L), any());
    }

    @Test
    void onOrderItemsSnapshot_nullOrderedAt_usesDefault() {
        materializer.onOrderItemsSnapshot(new OrderItemsSnapshotEvent(
                100L, "ORD-100", 1L,
                List.of(item(1L, "Item", 1)),
                null));

        verify(trendingDishRepository).upsert(eq(1L), eq(1L), eq("Item"), eq(1L), any());
    }

    @Test
    void onOrderItemsSnapshot_upsertFailure_propagatesForOutboxRetry() {
        org.mockito.Mockito.doThrow(new RuntimeException("db error"))
                .when(trendingDishRepository).upsert(eq(1L), eq(1L), eq("Butter Chicken"), eq(2L), any());

        // The exception must propagate so the outbox processor can retry the
        // event (at-least-once delivery); the upsert is idempotent.
        assertThrows(RuntimeException.class,
                () -> materializer.onOrderItemsSnapshot(
                        eventWithItems(List.of(item(1L, "Butter Chicken", 2)))));
    }
}
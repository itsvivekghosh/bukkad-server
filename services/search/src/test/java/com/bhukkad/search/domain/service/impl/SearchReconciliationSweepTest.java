package com.bhukkad.search.domain.service.impl;

import com.bhukkad.search.config.SearchSyncProperties;
import com.bhukkad.search.infrastructure.client.SearchSourceClient;
import com.bhukkad.search.infrastructure.client.SearchSourceClient.SourceMenu;
import com.bhukkad.search.infrastructure.client.SearchSourceClient.SourceMenuItem;
import com.bhukkad.search.infrastructure.client.SearchSourceClient.SourceRestaurant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-002 reconciliation sweep: bounded batches from the source, per-restaurant
 * repair in its own transaction, poison rows skipped (id ≤ 0 / null),
 * source-outage retry, delete propagation of stale projected items and
 * failure isolation — one exploding restaurant never aborts the cycle.
 */
@ExtendWith(MockitoExtension.class)
class SearchReconciliationSweepTest {

    @Mock private SearchSourceClient sourceClient;
    @Mock private SearchSyncProjectionService projectionService;
    @Mock private MenuItemProjectionReader menuItemReader;
    @Mock private PlatformTransactionManager txManager;

    private final SearchSyncProperties properties = new SearchSyncProperties();

    private SearchReconciliationSweep sweep;

    @BeforeEach
    void init() {
        lenient().when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        sweep = new SearchReconciliationSweep(
                sourceClient, projectionService, menuItemReader, properties, txManager);
    }

    @Test
    void emptyPage_noRepairWork() {
        when(sourceClient.restaurantPage(0, 20)).thenReturn(List.of());

        sweep.sweep();

        verify(projectionService, never()).upsertMenuItemFromSource(
                anyLong(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void repairsItemsAndRemovesStaleRows() {
        when(sourceClient.restaurantPage(0, 20)).thenReturn(List.of(
                new SourceRestaurant(5L, "Spice", "North Indian", true)));
        when(sourceClient.menu(5L)).thenReturn(new SourceMenu(5L, "Spice", List.of(
                new SourceMenuItem(50L, "Butter Chicken", "creamy", 320.0, true),
                new SourceMenuItem(null, "corrupt line", null, null, null))));
        when(menuItemReader.countById(50L)).thenReturn(0);
        when(menuItemReader.idsForRestaurant(5L)).thenReturn(List.of(50L, 77L));

        sweep.sweep();

        verify(projectionService).upsertMenuItemFromSource(
                50L, 5L, "Spice", "Butter Chicken", "creamy", 320.0, true);
        verify(projectionService).deleteMenuItem(eq(77L), isNull());
        verify(projectionService, never()).deleteMenuItem(eq(50L), any());
        verify(txManager).commit(any());
    }

    @Test
    void invalidSourceIds_areSkippedBeforeAnyTransaction() {
        when(sourceClient.restaurantPage(0, 20)).thenReturn(List.of(
                new SourceRestaurant(null, "ghost", null, true),
                new SourceRestaurant(-3L, "negative", null, true)));

        sweep.sweep();

        verify(sourceClient, never()).menu(any());
        verify(txManager, never()).getTransaction(any());
    }

    @Test
    void sourceOutageForOneRestaurant_defersWithoutFailingCycle() {
        when(sourceClient.restaurantPage(0, 20)).thenReturn(List.of(
                new SourceRestaurant(5L, "Spice", null, true)));
        when(sourceClient.menu(5L)).thenReturn(null);

        sweep.sweep();

        verify(projectionService, never()).upsertMenuItemFromSource(
                anyLong(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void oneRestaurantFailure_doesNotAbortTheSweep() {
        when(sourceClient.restaurantPage(0, 20)).thenReturn(List.of(
                new SourceRestaurant(5L, "boom", null, true),
                new SourceRestaurant(6L, "ok", null, true)));
        when(sourceClient.menu(5L)).thenThrow(new RuntimeException("source exploded"));
        when(sourceClient.menu(6L)).thenReturn(new SourceMenu(6L, "ok", List.of(
                new SourceMenuItem(60L, "Dal", null, 180.0, true))));
        when(menuItemReader.countById(60L)).thenReturn(1);
        when(menuItemReader.idsForRestaurant(6L)).thenReturn(List.of(60L));

        sweep.sweep();

        verify(projectionService).upsertMenuItemFromSource(
                60L, 6L, "ok", "Dal", null, 180.0, true);
    }
}

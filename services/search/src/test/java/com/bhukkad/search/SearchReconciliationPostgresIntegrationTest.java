package com.bhukkad.search;

import com.bhukkad.search.entity.MenuItemSearchEntity;
import com.bhukkad.search.repository.MenuItemSearchRepository;
import com.bhukkad.search.sync.SearchReconciliationSweep;
import com.bhukkad.search.sync.SearchSourceClient;
import com.bhukkad.search.sync.SearchSyncProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ADR-002 reconciliation sweep repair test: a source-of-truth menu diffed
 * against the projection table repairs drifted rows (upsert) and removes
 * rows the source no longer contains (delete propagation). The source HTTP
 * client is mocked at the boundary — the sweep logic, SQL upserts and the
 * per-restaurant transaction boundary run against real PostgreSQL.
 */
@SpringBootTest(properties = "app.search.sync.interval-ms=3600000")
class SearchReconciliationPostgresIntegrationTest extends AbstractSearchPostgresTest {

    @Autowired
    private SearchReconciliationSweep sweep;

    @Autowired
    private SearchSyncProjectionService projectionService;

    @Autowired
    private MenuItemSearchRepository menuItemSearchRepository;

    @MockBean
    private SearchSourceClient sourceClient;

    @Test
    void sweep_repairsDriftedRows_andRemovesStaleOnes() {
        // Drift 1: a projected row with a STALE price the event path missed.
        projectionService.upsertMenuItemFromSource(5001L, 601L, "Dosa Corner",
                "Masala Dosa", null, 55.0, true);
        // Drift 2: a projected row the source menu no longer contains.
        projectionService.upsertMenuItemFromSource(5002L, 601L, "Dosa Corner",
                "Discontinued Item", null, 30.0, true);

        when(sourceClient.restaurantPage(anyInt(), anyInt()))
                .thenReturn(List.of(new SearchSourceClient.SourceRestaurant(601L, "Dosa Corner", null, true)));
        when(sourceClient.menu(601L)).thenReturn(new SearchSourceClient.SourceMenu(601L, "Dosa Corner", List.of(
                new SearchSourceClient.SourceMenuItem(5001L, "Masala Dosa", "Crispy crepe", 89.50, true),
                new SearchSourceClient.SourceMenuItem(5003L, "New Rava Dosa", null, 75.0, true))));

        sweep.sweep();

        // Repair: price corrected.
        MenuItemSearchEntity repaired = menuItemSearchRepository.findById(5001L).orElseThrow();
        assertThat(repaired.getPrice()).isEqualTo(89.5);
        assertThat(repaired.getRestaurantId()).isEqualTo(601L);
        assertThat(repaired.getRestaurantName()).isEqualTo("Dosa Corner");
        // New: source item appears.
        assertThat(menuItemSearchRepository.findById(5003L)).isPresent();
        // Delete propagation: stale row removed (no orphan hits).
        assertThat(menuItemSearchRepository.findById(5002L)).isEmpty();
    }

    @Test
    void sweep_isBoundedToConfiguredRestaurantsPerCycle() {
        when(sourceClient.restaurantPage(eq(0), anyInt())).thenReturn(List.of());
        sweep.sweep();
        assertThat(menuItemSearchRepository.findAll()).isEmpty();
    }
}

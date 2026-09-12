package com.bhukkad.admin.api;
import com.bhukkad.admin.api.controller.AdminGrowthController;

import com.bhukkad.admin.domain.entity.RestaurantOrderStat;
import com.bhukkad.admin.domain.repository.RestaurantOrderStatRepository;
import com.bhukkad.admin.domain.service.AdminQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminGrowthControllerTest {

    @Mock private RestaurantOrderStatRepository statRepository;
    @Mock private AdminQueryService queryService;
    @InjectMocks private AdminGrowthController controller;

    private RestaurantOrderStat stat(long restaurantId, long orderCount) {
        RestaurantOrderStat stat = new RestaurantOrderStat();
        stat.setRestaurantId(restaurantId);
        stat.setOrderCount(orderCount);
        return stat;
    }

    @Test
    void restaurantStats_returnsBoundedPage() {
        when(queryService.restaurantStats()).thenReturn(List.of(stat(1L, 10)));

        assertThat(controller.restaurantStats()).hasSize(1);
        // PERF-3: delegates to the bounded page (no whole-table findAll here).
        verify(queryService).restaurantStats();
        verify(statRepository, org.mockito.Mockito.never()).findAll();
    }

    @Test
    void topRestaurants_ordersByOrderCountDescInSql() {
        when(statRepository.findAll(org.mockito.ArgumentMatchers
                .any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(stat(2L, 50), stat(3L, 20), stat(1L, 5)),
                        org.springframework.data.domain.PageRequest.of(0, 10), 3));

        List<RestaurantOrderStat> top = controller.topRestaurants(10);

        assertThat(top).extracting(RestaurantOrderStat::getRestaurantId)
                .containsExactly(2L, 3L, 1L);
        // The sort + limit must be expressed through the Pageable (SQL side).
        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(statRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(10);
        assertThat(captor.getValue().getSort().getOrderFor("orderCount")).isNotNull();
        assertThat(captor.getValue().getSort().getOrderFor("orderCount").getDirection())
                .isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
    }

    @Test
    void topRestaurants_honorsLimitAndPageCap() {
        when(statRepository.findAll(org.mockito.ArgumentMatchers
                .any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(stat(2L, 50), stat(3L, 20)),
                        org.springframework.data.domain.PageRequest.of(0, 2), 5));

        assertThat(controller.topRestaurants(2)).hasSize(2);
        // Over-cap requests clamp to 200 rows (list shape kept, additively capped).
        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        controller.topRestaurants(5_000);
        verify(statRepository, org.mockito.Mockito.times(2)).findAll(captor.capture());
        assertThat(captor.getAllValues().get(1).getPageSize()).isEqualTo(200);
    }

    @Test
    void fraudSummary_delegatesToQueryService() {
        when(queryService.fraudAlerts(null)).thenReturn(List.of());

        assertThat(controller.fraudSummary(null)).isNotNull();
        verify(queryService).fraudAlerts(null);
    }
}

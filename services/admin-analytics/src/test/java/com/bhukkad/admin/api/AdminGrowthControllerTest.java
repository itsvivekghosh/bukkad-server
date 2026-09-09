package com.bhukkad.admin.api;

import com.bhukkad.admin.domain.RestaurantOrderStat;
import com.bhukkad.admin.domain.RestaurantOrderStatRepository;
import com.bhukkad.admin.service.AdminQueryService;
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
    void restaurantStats_returnsAllStats() {
        when(statRepository.findAll()).thenReturn(List.of(stat(1L, 10)));

        assertThat(controller.restaurantStats()).hasSize(1);
        verify(statRepository).findAll();
    }

    @Test
    void topRestaurants_sortsByOrderCountDescending() {
        when(statRepository.findAll())
                .thenReturn(List.of(stat(1L, 5), stat(2L, 50), stat(3L, 20)));

        List<RestaurantOrderStat> top = controller.topRestaurants(10);

        assertThat(top).extracting(RestaurantOrderStat::getRestaurantId)
                .containsExactly(2L, 3L, 1L);
    }

    @Test
    void topRestaurants_honorsLimit() {
        when(statRepository.findAll())
                .thenReturn(List.of(stat(1L, 5), stat(2L, 50), stat(3L, 20)));

        assertThat(controller.topRestaurants(2)).hasSize(2);
    }

    @Test
    void fraudSummary_delegatesToQueryService() {
        when(queryService.fraudAlerts(null)).thenReturn(List.of());

        assertThat(controller.fraudSummary(null)).isNotNull();
        verify(queryService).fraudAlerts(null);
    }
}

package com.bhukkad.zone;

import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemandForecastServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private DemandForecastService service;

    @BeforeEach
    void setUp() {
        service = new DemandForecastService(orderRepository);
    }

    @Test
    void forecastSurgeAdjustment_returnsNeutralWhenNoHistory() {
        when(orderRepository.findPlatformHourlyOrderCounts(any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertEquals(1.0, service.forecastSurgeAdjustment());
    }

    @Test
    void forecastSurgeAdjustment_isClampedToBounds() {
        // One day of data: every hour has 1 order except the current hour which
        // has 100, so the current-hour average is far above the platform mean.
        int nowHour = LocalDateTime.now().getHour();
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int h = 0; h < 24; h++) {
            rows.add(new Object[]{h, 1L});
        }
        rows.set(nowHour, new Object[]{nowHour, 100L});
        when(orderRepository.findPlatformHourlyOrderCounts(any(LocalDateTime.class)))
                .thenReturn(rows);

        double adjustment = service.forecastSurgeAdjustment();

        // Clamped to MAX_ADJUSTMENT (2.0) rather than the raw ~8.5 ratio.
        assertEquals(2.0, adjustment);
    }

    @Test
    void forecastSurgeAdjustment_returnsOneWhenRepositoryFails() {
        when(orderRepository.findPlatformHourlyOrderCounts(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("db down"));

        assertEquals(1.0, service.forecastSurgeAdjustment());
    }

    @Test
    void forecastSurgeAdjustment_returnsOneWhenAllHoursZero() {
        // All hours have 0 orders: average is 0, so the neutral 1.0 is returned.
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int h = 0; h < 24; h++) {
            rows.add(new Object[]{h, 0L});
        }
        when(orderRepository.findPlatformHourlyOrderCounts(any(LocalDateTime.class)))
                .thenReturn(rows);

        assertEquals(1.0, service.forecastSurgeAdjustment());
    }

    @Test
    void forecastSurgeAdjustment_handlesNullRows() {
        when(orderRepository.findPlatformHourlyOrderCounts(any(LocalDateTime.class)))
                .thenReturn(null);

        assertEquals(1.0, service.forecastSurgeAdjustment());
    }

    @Test
    void forecastSurgeAdjustment_ignoresOutOfRangeHours() {
        // Only one valid row, placed at the CURRENT hour; the invalid rows
        // (hour < 0 or >= 24) must be ignored by the forecast.
        int nowHour = LocalDateTime.now().getHour();
        List<Object[]> rows = List.of(
                new Object[]{-5, 100L},   // invalid hour, must be ignored
                new Object[]{24, 100L},   // invalid hour, must be ignored
                new Object[]{nowHour, 10L}); // valid
        when(orderRepository.findPlatformHourlyOrderCounts(any(LocalDateTime.class)))
                .thenReturn(rows);

        // The only valid row is the current hour, so the ratio is far above 1
        // and gets clamped to MAX_ADJUSTMENT (2.0).
        assertEquals(2.0, service.forecastSurgeAdjustment());
    }
}

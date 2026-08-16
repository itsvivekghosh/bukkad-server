package com.bhukkad.service;

import com.bhukkad.entity.FraudEvent;
import com.bhukkad.repository.FraudEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudDashboardServiceTest {

    @Mock
    private FraudEventRepository fraudEventRepository;

    @InjectMocks
    private FraudDashboardService service;

    @Test
    void getDashboard_noEvents_returnsEmptyStats() {
        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of());

        var dashboard = service.getDashboard();

        assertEquals(0, dashboard.getTotalEvents());
        assertEquals(0, dashboard.getEventsLast24Hours());
        assertTrue(dashboard.getEventsByType().isEmpty());
        assertTrue(dashboard.getTopIPs().isEmpty());
        assertTrue(dashboard.getTopDevices().isEmpty());
        assertTrue(dashboard.getRecentEvents().isEmpty());
    }

    @Test
    void getDashboard_withEvents_returnsStats() {
        LocalDateTime now = LocalDateTime.now();
        FraudEvent event1 = new FraudEvent();
        event1.setId(1L);
        event1.setEventType("ORDER_CREATE");
        event1.setIpAddress("192.168.1.1");
        event1.setDeviceFingerprint("device123");
        event1.setCreatedAt(now);

        FraudEvent event2 = new FraudEvent();
        event2.setId(2L);
        event2.setEventType("AUTH_LOGIN");
        event2.setIpAddress("192.168.1.2");
        event2.setDeviceFingerprint("device456");
        event2.setCreatedAt(now.minusHours(2));

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(event1, event2));

        var dashboard = service.getDashboard();

        assertEquals(2, dashboard.getTotalEvents());
        assertEquals(2, dashboard.getEventsLast24Hours());
        assertEquals(2, dashboard.getEventsLast7Days());
        assertEquals(2, dashboard.getEventsLast30Days());
        assertEquals(2, dashboard.getEventsByType().size());
        assertEquals(2, dashboard.getTopIPs().size());
        assertEquals(2, dashboard.getTopDevices().size());
        assertEquals(2, dashboard.getRecentEvents().size());
    }

    @Test
    void getEventsForReview_returnsRecentEvents() {
        FraudEvent event = new FraudEvent();
        event.setId(1L);
        event.setEventType("ORDER_CREATE");
        event.setCreatedAt(LocalDateTime.now());

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(event));

        var events = service.getEventsForReview();

        assertEquals(1, events.size());
        assertEquals("ORDER_CREATE", events.get(0).getEventType());
    }

    @Test
    void getDashboard_filtersNullIPsAndDevices() {
        FraudEvent event = new FraudEvent();
        event.setId(1L);
        event.setEventType("ORDER_CREATE");
        event.setIpAddress(null);
        event.setDeviceFingerprint(null);
        event.setCreatedAt(LocalDateTime.now());

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(event));

        var dashboard = service.getDashboard();

        assertTrue(dashboard.getTopIPs().isEmpty());
        assertTrue(dashboard.getTopDevices().isEmpty());
    }
}
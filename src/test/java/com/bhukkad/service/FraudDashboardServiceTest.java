package com.bhukkad.service;

import com.bhukkad.dto.response.FraudDashboardResponse;
import com.bhukkad.dto.response.FraudEventResponse;
import com.bhukkad.dto.response.FraudPatternResponse;
import com.bhukkad.entity.FraudEvent;
import com.bhukkad.entity.FraudReviewAction;
import com.bhukkad.repository.FraudEventRepository;
import com.bhukkad.repository.FraudReviewActionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudDashboardServiceTest {

    @Mock
    private FraudEventRepository fraudEventRepository;

    @Mock
    private FraudReviewActionRepository fraudReviewActionRepository;

    @InjectMocks
    private FraudDashboardService service;

    private FraudEvent event(long id, String type, String ip, String device, LocalDateTime createdAt) {
        FraudEvent event = new FraudEvent();
        event.setId(id);
        event.setEventType(type);
        event.setIpAddress(ip);
        event.setDeviceFingerprint(device);
        event.setCreatedAt(createdAt);
        return event;
    }

    @Test
    void getDashboard_noEvents_returnsEmptyStats() {
        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .thenReturn(0L);

        FraudDashboardResponse dashboard = service.getDashboard();

        assertEquals(0, dashboard.getTotalEvents());
        assertEquals(0, dashboard.getEventsLast24Hours());
        assertEquals(0, dashboard.getEventsLast7Days());
        assertEquals(0, dashboard.getEventsLast30Days());
        assertEquals(0, dashboard.getPendingReviewCount());
        assertTrue(dashboard.getEventsByType().isEmpty());
        assertTrue(dashboard.getTopIPs().isEmpty());
        assertTrue(dashboard.getTopDevices().isEmpty());
        assertTrue(dashboard.getRecentEvents().isEmpty());
    }

    @Test
    void getDashboard_reportsPendingReviewCount() {
        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .thenReturn(3L);

        FraudDashboardResponse dashboard = service.getDashboard();

        assertEquals(3, dashboard.getPendingReviewCount());
    }

    @Test
    void getDashboard_withRecentEvents_returnsStatsAndMappings() {
        LocalDateTime now = LocalDateTime.now();
        FraudEvent event1 = event(1L, "ORDER_CREATE", "192.168.1.1", "device123", now);
        FraudEvent event2 = event(2L, "AUTH_LOGIN", "192.168.1.2", "device456", now.minusHours(2));

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(event1, event2));
        when(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .thenReturn(0L);

        FraudDashboardResponse dashboard = service.getDashboard();

        assertEquals(2, dashboard.getTotalEvents());
        assertEquals(2, dashboard.getEventsLast24Hours());
        assertEquals(2, dashboard.getEventsLast7Days());
        assertEquals(2, dashboard.getEventsLast30Days());
        assertEquals(2, dashboard.getEventsByType().size());
        assertEquals(1L, dashboard.getEventsByType().get("ORDER_CREATE"));
        assertEquals(1L, dashboard.getEventsByType().get("AUTH_LOGIN"));
        assertEquals(2, dashboard.getTopIPs().size());
        assertEquals(2, dashboard.getTopDevices().size());
        assertEquals(2, dashboard.getRecentEvents().size());

        FraudEventResponse mapped = dashboard.getRecentEvents().get(0);
        assertEquals(1L, mapped.getId());
        assertEquals("ORDER_CREATE", mapped.getEventType());
        assertEquals("192.168.1.1", mapped.getIpAddress());
        assertEquals("device123", mapped.getDeviceFingerprint());
        assertEquals(now.toString(), mapped.getCreatedAt());
    }

    @Test
    void getDashboard_filtersNullIPsAndDevices() {
        FraudEvent event = event(1L, "ORDER_CREATE", null, null, LocalDateTime.now());
        event.setDetails("suspicious");

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(event));
        when(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .thenReturn(0L);

        FraudDashboardResponse dashboard = service.getDashboard();

        assertTrue(dashboard.getTopIPs().isEmpty());
        assertTrue(dashboard.getTopDevices().isEmpty());
        assertEquals(1, dashboard.getEventsByType().size());
        assertEquals(1, dashboard.getRecentEvents().size());
        assertEquals("suspicious", dashboard.getRecentEvents().get(0).getDetails());
    }

    @Test
    void getDashboard_windowBoundaries_partitionEventsByAge() {
        LocalDateTime now = LocalDateTime.now();
        FraudEvent within24h = event(1L, "A", "ip-1", "dev-1", now.minusHours(23));
        FraudEvent within7d = event(2L, "B", "ip-2", "dev-2", now.minusDays(6));
        FraudEvent within30d = event(3L, "C", "ip-3", "dev-3", now.minusDays(28));
        FraudEvent tooOld = event(4L, "D", "ip-4", "dev-4", now.minusDays(40));

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc())
                .thenReturn(List.of(within24h, within7d, within30d, tooOld));
        when(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .thenReturn(0L);

        FraudDashboardResponse dashboard = service.getDashboard();

        assertEquals(4, dashboard.getTotalEvents());
        assertEquals(1, dashboard.getEventsLast24Hours());
        assertEquals(2, dashboard.getEventsLast7Days());
        assertEquals(3, dashboard.getEventsLast30Days());
    }

    @Test
    void getDashboard_duplicateIPs_countedAndSortedDescending() {
        LocalDateTime now = LocalDateTime.now();
        List<FraudEvent> events = new ArrayList<>();
        events.add(event(1L, "LOGIN", "10.0.0.1", "d1", now));
        events.add(event(2L, "LOGIN", "10.0.0.1", "d2", now));
        events.add(event(3L, "LOGIN", "10.0.0.1", "d3", now));
        events.add(event(4L, "LOGIN", "10.0.0.2", "d4", now));
        events.add(event(5L, "LOGIN", "10.0.0.2", "d5", now));

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(events);
        when(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .thenReturn(0L);

        FraudDashboardResponse dashboard = service.getDashboard();

        assertEquals(2, dashboard.getTopIPs().size());
        FraudPatternResponse top = dashboard.getTopIPs().get(0);
        assertEquals("10.0.0.1", top.getIdentifier());
        assertEquals("IP", top.getType());
        assertEquals(3, top.getCount());
        assertEquals(2, dashboard.getTopIPs().get(1).getCount());
    }

    @Test
    void getDashboard_moreThanTenIPs_limitsToTen() {
        LocalDateTime now = LocalDateTime.now();
        List<FraudEvent> events = new ArrayList<>();
        for (long i = 1; i <= 12; i++) {
            events.add(event(i, "LOGIN", "10.0.0." + i, "dev-" + i, now));
        }

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(events);
        when(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .thenReturn(0L);

        FraudDashboardResponse dashboard = service.getDashboard();

        assertEquals(10, dashboard.getTopIPs().size());
        assertEquals(10, dashboard.getTopDevices().size());
    }

    @Test
    void getDashboard_moreThanTwentyEvents_limitsRecentEventsToTwenty() {
        LocalDateTime now = LocalDateTime.now();
        List<FraudEvent> events = new ArrayList<>();
        for (long i = 1; i <= 25; i++) {
            events.add(event(i, "LOGIN", "10.0.0." + i, "dev-" + i, now));
        }

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(events);
        when(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .thenReturn(0L);

        FraudDashboardResponse dashboard = service.getDashboard();

        assertEquals(20, dashboard.getRecentEvents().size());
        assertEquals(25, dashboard.getTotalEvents());
    }

    @Test
    void getEventsForReview_returnsRecentEvents() {
        FraudEvent event = event(1L, "ORDER_CREATE", "1.1.1.1", "dev", LocalDateTime.now());

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(event));

        List<FraudEvent> events = service.getEventsForReview();

        assertEquals(1, events.size());
        assertEquals("ORDER_CREATE", events.get(0).getEventType());
    }

    @Test
    void getEventsForReview_empty_returnsEmptyList() {
        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of());

        assertTrue(service.getEventsForReview().isEmpty());
    }

    @Test
    void getDashboard_nullDetails_mappedAsNull() {
        FraudEvent event = event(1L, "ORDER_CREATE", "1.1.1.1", "dev", LocalDateTime.now());

        when(fraudEventRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(event));
        when(fraudReviewActionRepository.countByStatus(FraudReviewAction.FraudReviewStatus.PENDING))
                .thenReturn(0L);

        FraudDashboardResponse dashboard = service.getDashboard();

        assertNull(dashboard.getRecentEvents().get(0).getDetails());
    }
}

package com.bhukkad.controller;

import com.bhukkad.dto.request.FraudReviewActionRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.FraudDashboardResponse;
import com.bhukkad.dto.response.FraudEventResponse;
import com.bhukkad.dto.response.FraudReviewActionResponse;
import com.bhukkad.entity.FraudEvent;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.FraudDashboardService;
import com.bhukkad.service.FraudReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudDashboardControllerTest {

    @Mock
    private FraudDashboardService fraudDashboardService;
    @Mock
    private FraudReviewService fraudReviewService;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private FraudDashboardController controller;

    @Test
    void getDashboard_returnsDashboard() {
        FraudDashboardResponse dashboard = FraudDashboardResponse.builder().build();
        when(fraudDashboardService.getDashboard()).thenReturn(dashboard);

        ResponseEntity<ApiResponse<FraudDashboardResponse>> response = controller.getDashboard();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(dashboard, response.getBody().getData());
        verify(fraudDashboardService).getDashboard();
    }

    @Test
    void getEventsForReview_mapsEntitiesToResponses() {
        FraudEvent event = new FraudEvent();
        event.setId(9L);
        event.setEventType("REGISTRATION");
        event.setIpAddress("1.2.3.4");
        event.setDeviceFingerprint("fp-1");
        event.setDetails("suspicious signup");
        event.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        when(fraudDashboardService.getEventsForReview()).thenReturn(List.of(event));

        ResponseEntity<ApiResponse<List<FraudEventResponse>>> response = controller.getEventsForReview();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<FraudEventResponse> events = response.getBody().getData();
        assertEquals(1, events.size());
        FraudEventResponse mapped = events.get(0);
        assertEquals(9L, mapped.getId());
        assertEquals("REGISTRATION", mapped.getEventType());
        assertEquals("1.2.3.4", mapped.getIpAddress());
        assertEquals("fp-1", mapped.getDeviceFingerprint());
        assertEquals("suspicious signup", mapped.getDetails());
        assertEquals("2026-01-01T10:00", mapped.getCreatedAt());
    }

    @Test
    void getReviewQueue_returnsPendingActions() {
        List<FraudReviewActionResponse> queue = List.of(FraudReviewActionResponse.builder().build());
        when(fraudReviewService.listPending()).thenReturn(queue);

        ResponseEntity<ApiResponse<List<FraudReviewActionResponse>>> response = controller.getReviewQueue();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(queue, response.getBody().getData());
    }

    @Test
    void reviewEvent_forwardsAdminIdFromSecurityContext() {
        FraudReviewActionRequest request = new FraudReviewActionRequest();
        FraudReviewActionResponse response = FraudReviewActionResponse.builder().build();
        when(securityUtils.getCurrentUserId()).thenReturn(77L);
        when(fraudReviewService.action(12L, request, 77L)).thenReturn(response);

        ResponseEntity<ApiResponse<FraudReviewActionResponse>> result = controller.reviewEvent(12L, request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("Fraud event reviewed", result.getBody().getMessage());
        assertSame(response, result.getBody().getData());
        verify(fraudReviewService).action(12L, request, 77L);
    }
}

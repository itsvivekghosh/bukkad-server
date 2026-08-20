package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.FraudReviewActionRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.FraudDashboardResponse;
import com.bhukkad.dto.response.FraudEventResponse;
import com.bhukkad.dto.response.FraudReviewActionResponse;
import com.bhukkad.entity.FraudEvent;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.FraudDashboardService;
import com.bhukkad.service.FraudReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/admin/fraud")
@RequiredArgsConstructor
public class FraudDashboardController {

    private final FraudDashboardService fraudDashboardService;
    private final FraudReviewService fraudReviewService;
    private final SecurityUtils securityUtils;

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FraudDashboardResponse>> getDashboard() {
        FraudDashboardResponse dashboard = fraudDashboardService.getDashboard();
        return ResponseEntity.ok(ApiResponse.success(dashboard));
    }

    @GetMapping("/events")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<FraudEventResponse>>> getEventsForReview() {
        List<FraudEvent> events = fraudDashboardService.getEventsForReview();
        List<FraudEventResponse> responses = events.stream()
                .map(event -> FraudEventResponse.builder()
                        .id(event.getId())
                        .eventType(event.getEventType())
                        .ipAddress(event.getIpAddress())
                        .deviceFingerprint(event.getDeviceFingerprint())
                        .details(event.getDetails())
                        .createdAt(event.getCreatedAt().toString())
                        .build())
                .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    /** Manual review queue: actions an admin has taken (or will take) on fraud events. */
    @GetMapping("/review-queue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<FraudReviewActionResponse>>> getReviewQueue() {
        return ResponseEntity.ok(ApiResponse.success(fraudReviewService.listPending()));
    }

    /** Records an admin decision (BLOCK_CUSTOMER / IGNORE) on one fraud event. */
    @PostMapping("/review-queue/{eventId}/action")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FraudReviewActionResponse>> reviewEvent(
            @PathVariable Long eventId, @Valid @RequestBody FraudReviewActionRequest request) {
        FraudReviewActionResponse response = fraudReviewService.action(
                eventId, request, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Fraud event reviewed", response));
    }
}
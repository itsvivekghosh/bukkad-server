package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.FraudDashboardResponse;
import com.bhukkad.dto.response.FraudEventResponse;
import com.bhukkad.entity.FraudEvent;
import com.bhukkad.service.FraudDashboardService;
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
}
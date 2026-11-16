package com.bhukkad.engagement.support.chat.api.controller;

import com.bhukkad.engagement.support.chat.api.dto.response.FraudAlertResponse;
import com.bhukkad.engagement.support.chat.fraud.FraudDetectionEngine.FraudStats;
import com.bhukkad.engagement.support.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin controller for fraud alert management.
 *
 * <p>Endpoints for reviewing and acting on fraud alerts detected by the
 * fraud detection engine.</p>
 */
@Tag(name = "Fraud Alerts", description = "Fraud alert management for administrators")
@RestController
@RequestMapping("/api/v1/support/chat/fraud/alerts")
public class FraudAlertController {

    private final ChatService chatService;

    public FraudAlertController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Get fraud statistics for dashboard.
     */
    @Operation(summary = "Get fraud statistics", description = "Returns fraud statistics for admin dashboard")
    @GetMapping("/stats")
    public ResponseEntity<FraudStats> getStats(
            @RequestParam(required = false) LocalDateTime since) {
        if (since == null) {
            since = LocalDateTime.now().minusDays(7);
        }
        FraudStats stats = chatService.getFraudStats(since);
        return ResponseEntity.ok(stats);
    }
}

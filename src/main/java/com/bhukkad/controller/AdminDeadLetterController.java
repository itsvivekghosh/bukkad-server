package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.DeadLetterEventResponse;
import com.bhukkad.outbox.DeadLetterEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only endpoints for the outbox dead-letter queue (DLQ): inspect failed
 * events with payload previews and re-drive individual events back into the
 * outbox for retry. Complements the {@code DeadLetterEventService} used by the
 * outbox processor.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/admin/outbox/dlq")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin DLQ", description = "Inspect and replay dead-lettered outbox events")
public class AdminDeadLetterController {

    private final DeadLetterEventService deadLetterEventService;

    @GetMapping
    @Operation(summary = "List dead-letter events", description = "Returns the most recent DLQ rows (newest first) with payload previews")
    public ResponseEntity<ApiResponse<List<DeadLetterEventResponse>>> list(
            @RequestParam(defaultValue = "20") int limit) {
        List<DeadLetterEventResponse> events = deadLetterEventService.listRecent(limit).stream()
                .map(DeadLetterEventResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(events));
    }

    @GetMapping("/pending/count")
    @Operation(summary = "Count pending dead-letter events", description = "Number of DLQ rows still awaiting replay")
    public ResponseEntity<ApiResponse<Long>> countPending() {
        return ResponseEntity.ok(ApiResponse.success(deadLetterEventService.countPending()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get dead-letter event", description = "Returns a single DLQ row including its full payload preview")
    public ResponseEntity<ApiResponse<DeadLetterEventResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                DeadLetterEventResponse.from(deadLetterEventService.getById(id))));
    }

    @PostMapping("/{id}/requeue")
    @Operation(summary = "Requeue dead-letter event", description = "Re-drives one DLQ row back into the outbox for retry (idempotent)")
    public ResponseEntity<ApiResponse<DeadLetterEventResponse>> requeue(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                DeadLetterEventResponse.from(deadLetterEventService.requeueOne(id))));
    }
}

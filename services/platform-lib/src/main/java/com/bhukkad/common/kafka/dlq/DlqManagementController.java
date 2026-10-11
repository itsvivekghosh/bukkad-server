package com.bhukkad.common.kafka.dlq;

import com.bhukkad.common.web.ApiError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for DLQ management.
 *
 * <p>Provides endpoints for inspecting and reprocessing dead-letter events.
 * Requires ADMIN role.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/dlq")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class DlqManagementController {

    private final DlqConsumerService consumerService;
    private final DlqReprocessService reprocessService;

    /**
     * Lists pending dead-letter events.
     */
    @GetMapping
    public ResponseEntity<?> listDeadLetters(
            @RequestParam(defaultValue = "100") int limit) {
        try {
            List<DlqConsumerService.DlqEvent> events = consumerService.fetchDeadLetters(limit);
            long pendingCount = consumerService.getPendingCount();
            
            return ResponseEntity.ok(Map.of(
                    "events", events,
                    "pendingCount", pendingCount,
                    "fetched", events.size()
            ));
        } catch (Exception e) {
            log.error("Failed to list DLQ events: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiError.of(500, "DLQ_FETCH_FAILED", "Failed to fetch DLQ events: " + e.getMessage()));
        }
    }

    /**
     * Gets the count of pending DLQ messages.
     */
    @GetMapping("/count")
    public ResponseEntity<?> getPendingCount() {
        try {
            long count = consumerService.getPendingCount();
            return ResponseEntity.ok(Map.of("pendingCount", count));
        } catch (Exception e) {
            log.error("Failed to get DLQ count: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiError.of(500, "DLQ_COUNT_FAILED", "Failed to get DLQ count: " + e.getMessage()));
        }
    }

    /**
     * Reprocesses a single DLQ event.
     */
    @PostMapping("/reprocess")
    public ResponseEntity<?> reprocessEvent(@RequestBody DlqReprocessService.ReprocessRequest request) {
        try {
            DlqReprocessService.ReprocessResult result = reprocessService.reprocessEvent(
                    request.getTopic(), request.getPartition(), request.getOffset(), request.getTargetTopic());
            
            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", result.getMessage(),
                        "cacheKey", result.getCacheKey()
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", result.getMessage()
                ));
            }
        } catch (Exception e) {
            log.error("Failed to reprocess DLQ event: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiError.of(500, "DLQ_REPROCESS_FAILED", "Failed to reprocess: " + e.getMessage()));
        }
    }

    /**
     * Batch reprocesses multiple DLQ events.
     */
    @PostMapping("/reprocess/batch")
    public ResponseEntity<?> reprocessBatch(@RequestBody List<DlqReprocessService.ReprocessRequest> requests) {
        try {
            DlqReprocessService.BatchReprocessResult result = reprocessService.reprocessBatch(requests);
            
            return ResponseEntity.ok(Map.of(
                    "success", result.getSuccess(),
                    "failed", result.getFailed(),
                    "results", result.getResults()
            ));
        } catch (Exception e) {
            log.error("Failed to batch reprocess DLQ events: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiError.of(500, "DLQ_BATCH_REPROCESS_FAILED", "Failed to batch reprocess: " + e.getMessage()));
        }
    }

    /**
     * Deletes (skips) a DLQ event by committing its offset without reprocessing.
     * Use with caution - this permanently removes the event from the DLQ.
     */
    @DeleteMapping("/skip")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> skipEvent(@RequestParam String topic,
                                        @RequestParam int partition,
                                        @RequestParam long offset) {
        try {
            var consumer = consumerService.getDlqConsumer();
            var topicPartition = new org.apache.kafka.common.TopicPartition(topic, partition);
            consumer.assign(java.util.Collections.singletonList(topicPartition));
            consumer.seek(topicPartition, offset + 1);
            consumer.commitSync();
            
            log.warn("DLQ_SKIP | topic={} partition={} offset={}", topic, partition, offset);
            return ResponseEntity.ok(Map.of("success", true, "message", "Event skipped"));
        } catch (Exception e) {
            log.error("Failed to skip DLQ event: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiError.of(500, "DLQ_SKIP_FAILED", "Failed to skip event: " + e.getMessage()));
        }
    }
}
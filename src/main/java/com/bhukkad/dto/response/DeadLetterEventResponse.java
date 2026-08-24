package com.bhukkad.dto.response;

import com.bhukkad.outbox.DeadLetterEvent;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Admin-facing view of a dead-letter event. Includes the raw payload so an
 * operator can inspect why a message failed without touching the database.
 */
@Data
@Builder
public class DeadLetterEventResponse {

    private Long id;
    private String eventType;
    private String aggregateType;
    private Long aggregateId;
    private String payload;
    private String lastError;
    private int retryCount;
    private String source;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime requeuedAt;

    /** Maps a DLQ entity to its admin response (truncates oversized payloads). */
    public static DeadLetterEventResponse from(DeadLetterEvent event) {
        return DeadLetterEventResponse.builder()
                .id(event.getId())
                .eventType(event.getEventType())
                .aggregateType(event.getAggregateType())
                .aggregateId(event.getAggregateId())
                .payload(truncate(event.getPayload()))
                .lastError(event.getLastError())
                .retryCount(event.getRetryCount())
                .source(event.getSource())
                .status(event.getStatus() != null ? event.getStatus().name() : null)
                .createdAt(event.getCreatedAt())
                .requeuedAt(event.getRequeuedAt())
                .build();
    }

    private static String truncate(String payload) {
        if (payload == null) {
            return null;
        }
        return payload.length() <= 2000 ? payload : payload.substring(0, 2000) + "...(truncated)";
    }
}

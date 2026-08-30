package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Admin-facing view of a dead-letter event. Includes the raw payload so an
 * operator can inspect why a message failed without touching the database.
 *
 * <p>Pure data carrier: entity-to-DTO mapping lives in
 * {@code outbox.DeadLetterEventResponseMapper} so this package has no
 * dependency on the outbox module (module-cycle guardrail).</p>
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
}

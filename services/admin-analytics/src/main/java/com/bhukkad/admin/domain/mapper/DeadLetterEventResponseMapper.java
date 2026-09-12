package com.bhukkad.admin.domain.mapper;

import com.bhukkad.common.outbox.DeadLetterEvent;

import com.bhukkad.admin.api.dto.response.DeadLetterEventResponse;

/**
 * Maps dead-letter entities to their admin-facing response. Lives beside the
 * entity (not on the DTO) so the DTO layer stays free of persistence-package
 * imports — the DTO package must never depend on infrastructure modules.
 */
public final class DeadLetterEventResponseMapper {

    private DeadLetterEventResponseMapper() {
    }

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

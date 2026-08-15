package com.bhukkad.event.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlatformEventMessage(
        String eventType,
        Long aggregateId,
        String payload,
        Instant publishedAt
) {
}

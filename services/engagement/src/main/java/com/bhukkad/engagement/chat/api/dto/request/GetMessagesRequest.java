package com.bhukkad.engagement.chat.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for paginating messages in a session.
 */
public record GetMessagesRequest(
        @NotNull(message = "Session ID is required")
        String sessionId,

        @Min(value = 0, message = "Page must be non-negative")
        Integer page,

        @Min(value = 1, message = "Page size must be at least 1")
        Integer size
) {
    public int getPage() {
        return page != null ? page : 0;
    }

    public int getSize() {
        return size != null ? size : 50;
    }
}
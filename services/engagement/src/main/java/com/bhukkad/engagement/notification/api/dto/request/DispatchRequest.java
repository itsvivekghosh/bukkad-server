package com.bhukkad.engagement.notification.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record DispatchRequest(
        @NotBlank String channel,
        @NotBlank String recipient,
        String template,
        String subject,
        String body
) {
}

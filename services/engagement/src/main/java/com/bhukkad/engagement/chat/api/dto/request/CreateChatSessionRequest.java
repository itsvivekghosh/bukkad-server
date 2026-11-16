package com.bhukkad.engagement.chat.api.dto.request;

import com.bhukkad.engagement.chat.domain.entity.ChatSession.Department;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new chat session.
 */
public record CreateChatSessionRequest(
        @NotNull(message = "Department is required")
        Department department,

        @Size(max = 200, message = "Subject must not exceed 200 characters")
        String subject,

        @Positive(message = "Order ID must be a positive number")
        Long orderId,

        @NotBlank(message = "Customer identifier is required")
        String customerIdentifier
) {}
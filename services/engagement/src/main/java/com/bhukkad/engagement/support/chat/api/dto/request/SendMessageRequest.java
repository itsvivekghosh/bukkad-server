package com.bhukkad.engagement.support.chat.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to send a message in a chat room.
 */
public record SendMessageRequest(
    @NotNull Long roomId,
    @NotNull Long senderId,
    @NotBlank @Size(max = 200) String senderName,
    @NotNull String senderRole, // CUSTOMER, AGENT, SYSTEM
    @NotNull String messageType, // TEXT, IMAGE, DOCUMENT, SYSTEM
    @Size(max = 4000) String content,
    Long attachmentId
) {}
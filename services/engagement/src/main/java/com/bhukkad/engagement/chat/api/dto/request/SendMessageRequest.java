package com.bhukkad.engagement.chat.api.dto.request;

import com.bhukkad.engagement.chat.domain.entity.ChatMessage.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Request DTO for sending a message in a chat session.
 */
public record SendMessageRequest(
        @NotNull(message = "Session ID is required")
        String sessionId,

        @NotNull(message = "Message type is required")
        MessageType messageType,

        @Size(max = 10000, message = "Message content must not exceed 10000 characters")
        String content,

        String replyToMessageId,

        List<MultipartFile> attachments
) {}
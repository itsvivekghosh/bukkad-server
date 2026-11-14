package com.bhukkad.engagement.support.chat.api.dto.response;

import com.bhukkad.engagement.support.chat.domain.entity.Attachment;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for chat messages.
 */
public record ChatMessageResponse(
    UUID publicId,
    Long roomId,
    Long senderId,
    String senderName,
    String senderRole,
    String messageType,
    String content,
    Long attachmentId,
    AttachmentResponse attachment,
    Integer fraudScore,
    String fraudReasons,
    Boolean isAutoFlagged,
    Boolean isRead,
    LocalDateTime createdAt
) {}
package com.bhukkad.engagement.chat.api.dto.response;

import com.bhukkad.engagement.chat.domain.entity.ChatMessage.MessageType;
import com.bhukkad.engagement.chat.domain.entity.ChatMessage.SenderRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for chat message data.
 */
public record ChatMessageResponse(
        UUID messageId,
        UUID sessionId,
        Long senderId,
        SenderRole senderRole,
        MessageType messageType,
        String content,
        String contentType,
        UUID replyToMessageId,
        Short sentimentScore,
        boolean isFraudFlagged,
        String fraudReason,
        LocalDateTime createdAt,
        List<AttachmentInfo> attachments
) {
    public record AttachmentInfo(
            UUID attachmentId,
            String fileName,
            String mimeType,
            String downloadUrl,
            Double fraudRiskScore,
            boolean isFraudFlagged
    ) {
        public static AttachmentInfo fromEntity(com.bhukkad.engagement.chat.domain.entity.Attachment att, String baseUrl) {
            return new AttachmentInfo(
                    att.getAttachmentId(),
                    att.getFileName(),
                    att.getMimeType(),
                    baseUrl + "/api/v1/chat/attachments/" + att.getAttachmentId() + "/download",
                    att.getFraudRiskScore() != null ? att.getFraudRiskScore() : 0.0,
                    att.getFraudRiskScore() != null && att.getFraudRiskScore() > 50
            );
        }
    }

    public static ChatMessageResponse fromEntity(com.bhukkad.engagement.chat.domain.entity.ChatMessage message, 
                                                  List<AttachmentInfo> attachments) {
        return new ChatMessageResponse(
                message.getMessageId(),
                message.getSessionId(),
                message.getSenderId(),
                message.getSenderRole(),
                message.getMessageType(),
                message.getContent(),
                message.getContentType(),
                message.getReplyToMessageId(),
                message.getSentimentScore(),
                message.isFraudFlagged(),
                message.getFraudReason(),
                message.getCreatedAt(),
                attachments
        );
    }
}
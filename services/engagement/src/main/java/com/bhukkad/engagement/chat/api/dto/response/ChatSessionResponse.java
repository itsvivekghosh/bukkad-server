package com.bhukkad.engagement.chat.api.dto.response;

import com.bhukkad.engagement.chat.domain.entity.ChatSession.Department;
import com.bhukkad.engagement.chat.domain.entity.ChatSession.Priority;
import com.bhukkad.engagement.chat.domain.entity.ChatSession.Status;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for chat session data.
 */
public record ChatSessionResponse(
        UUID sessionId,
        Long customerId,
        Long orderId,
        Department department,
        String subject,
        Status status,
        Priority priority,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime closedAt,
        Integer messageCount,
        Short satisfactionRating,
        String satisfactionComment
) {
    public static ChatSessionResponse fromEntity(com.bhukkad.engagement.chat.domain.entity.ChatSession session, long messageCount) {
        return new ChatSessionResponse(
                session.getSessionId(),
                session.getCustomerId(),
                session.getOrderId(),
                session.getDepartment(),
                session.getSubject(),
                session.getStatus(),
                session.getPriority(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getClosedAt(),
                (int) messageCount,
                session.getSatisfactionRating(),
                session.getSatisfactionComment()
        );
    }
}
package com.bhukkad.engagement.support.chat.api.dto.response;

import com.bhukkad.engagement.support.chat.domain.entity.ChatRoom.ChatRoomStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for chat room information.
 */
public record ChatRoomResponse(
    UUID publicId,
    Long customerId,
    String customerName,
    String customerEmail,
    String agentId,
    String agentName,
    Long orderId,
    Long deliveryId,
    Long restaurantId,
    ChatRoomStatus status,
    String subject,
    Integer fraudScore,
    Boolean fraudFlagged,
    String fraudReasons,
    Integer totalMessages,
    LocalDateTime lastMessageAt,
    LocalDateTime createdAt,
    LocalDateTime resolvedAt
) {}
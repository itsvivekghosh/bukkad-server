package com.bhukkad.engagement.support.chat.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * Request to create a new chat room.
 */
public record CreateChatRoomRequest(
    @NotNull Long customerId,
    @NotBlank @Size(max = 200) String customerName,
    @Size(max = 255) String customerEmail,
    Long orderId,
    Long deliveryId,
    Long restaurantId,
    @NotBlank @Size(max = 500) String subject,
    String initialMessage
) {}

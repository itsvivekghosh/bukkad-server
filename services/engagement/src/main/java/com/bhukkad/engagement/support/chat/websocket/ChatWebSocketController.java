package com.bhukkad.engagement.support.chat.websocket;

import com.bhukkad.engagement.support.chat.api.dto.response.ChatMessageResponse;
import com.bhukkad.engagement.support.chat.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * WebSocket controller for real-time chat messaging.
 *
 * <p>Handles WebSocket STOMP messages for chat rooms:</p>
 * <ul>
 *   <li>/app/chat.sendMessage - Send a message to a room</li>
 *   <li>/app/chat.joinRoom - Join a room (subscribe)</li>
 *   <li>/app/chat.leaveRoom - Leave a room (unsubscribe)</li>
 *   <li>/app/chat.typing - Typing indicator</li>
 * </ul>
 *
 * <p>Messages are broadcast to room subscribers via /topic/chat/{roomId}.</p>
 */
@Controller
public class ChatWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);

    private final ChatService chatService;
    private final SimpMessageSendingOperations messagingTemplate;

    public ChatWebSocketController(ChatService chatService,
                                   SimpMessageSendingOperations messagingTemplate) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Send a message via WebSocket.
     *
     * Expected payload:
     * {
     *   "roomId": 123,
     *   "senderId": 456,
     *   "senderName": "John Doe",
     *   "senderRole": "CUSTOMER",
     *   "messageType": "TEXT",
     *   "content": "Hello, I need help with my order"
     * }
     */
    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessageRequest request) {
        try {
            com.bhukkad.engagement.support.chat.api.dto.request.SendMessageRequest serviceRequest =
                new com.bhukkad.engagement.support.chat.api.dto.request.SendMessageRequest(
                    request.roomId(),
                    request.senderId(),
                    request.senderName(),
                    request.senderRole(),
                    request.messageType(),
                    request.content(),
                    request.attachmentId()
                );

            com.bhukkad.engagement.support.chat.api.dto.response.ChatMessageResponse response =
                chatService.sendMessage(serviceRequest);

            // Broadcast to room subscribers
            messagingTemplate.convertAndSend(
                "/topic/chat/" + request.roomId(),
                response
            );

            log.debug("Message sent via WebSocket: roomId={}, senderId={}, messageId={}",
                request.roomId(), request.senderId(), response.publicId());

        } catch (Exception e) {
            log.error("Failed to send WebSocket message", e);
            // Send error back to sender
            messagingTemplate.convertAndSendToUser(
                String.valueOf(request.senderId()),
                "/queue/errors",
                new WebSocketError("message_send_failed", e.getMessage())
            );
        }
    }

    /**
     * Join a chat room (subscribe to room topic).
     */
    @MessageMapping("/chat.joinRoom")
    public void joinRoom(@Payload ChatRoomRequest request, Principal principal) {
        log.info("User {} joining room {}", request.senderId(), request.roomId());
        // In production, validate user has access to room
        // Subscribe happens client-side via /topic/chat/{roomId}
    }

    /**
     * Leave a chat room (unsubscribe).
     */
    @MessageMapping("/chat.leaveRoom")
    public void leaveRoom(@Payload ChatRoomRequest request) {
        log.info("User {} leaving room {}", request.senderId(), request.roomId());
    }

    /**
     * Typing indicator - broadcast to room.
     */
    @MessageMapping("/chat.typing")
    public void typing(@Payload TypingIndicator request) {
        // Broadcast typing indicator to room
        messagingTemplate.convertAndSend(
            "/topic/chat/" + request.roomId() + "/typing",
            request
        );
    }

    /**
     * Mark messages as read.
     */
    @MessageMapping("/chat.markRead")
    public void markRead(@Payload ReadReceipt request) {
        // In production, update message read status in database
        log.debug("Messages marked as read: roomId={}, readerId={}",
            request.roomId(), request.readerId());
    }

    // ==================== Request/Response DTOs ====================

    public record ChatMessageRequest(
        Long roomId,
        Long senderId,
        String senderName,
        String senderRole,
        String messageType,
        String content,
        Long attachmentId
    ) {}

    public record ChatRoomRequest(
        Long roomId,
        Long senderId
    ) {}

    public record TypingIndicator(
        Long roomId,
        Long userId,
        String userName,
        boolean isTyping
    ) {}

    public record ReadReceipt(
        Long roomId,
        Long readerId
    ) {}

    public record WebSocketError(
        String error,
        String message
    ) {}
}

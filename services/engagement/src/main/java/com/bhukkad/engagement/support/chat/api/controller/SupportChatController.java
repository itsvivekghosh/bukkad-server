package com.bhukkad.engagement.support.chat.api.controller;

import com.bhukkad.engagement.support.chat.api.dto.request.CreateChatRoomRequest;
import com.bhukkad.engagement.support.chat.api.dto.request.SendMessageRequest;
import com.bhukkad.engagement.support.chat.api.dto.response.AttachmentResponse;
import com.bhukkad.engagement.support.chat.api.dto.response.ChatMessageResponse;
import com.bhukkad.engagement.support.chat.api.dto.response.ChatRoomResponse;
import com.bhukkad.engagement.support.chat.api.dto.response.FraudAlertResponse;
import com.bhukkad.engagement.support.chat.fraud.FraudDetectionEngine;
import com.bhukkad.engagement.support.chat.fraud.FraudDetectionEngine.FraudStats;
import com.bhukkad.engagement.support.chat.service.ChatService;
import com.bhukkad.engagement.support.chat.service.AttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REST API for chat support operations.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>POST /api/v1/support/chat/rooms - Create chat room</li>
 *   <li>GET /api/v1/support/chat/rooms - List customer's rooms</li>
 *   <li>GET /api/v1/support/chat/rooms/{roomId} - Get room details</li>
 *   <li>GET /api/v1/support/chat/rooms/{roomId}/messages - Get messages</li>
 *   <li>POST /api/v1/support/chat/rooms/{roomId}/messages - Send message</li>
 *   <li>POST /api/v1/support/chat/rooms/{roomId}/attachments - Upload attachment</li>
 *   <li>GET /api/v1/support/chat/fraud/alerts - Get fraud alerts (admin)</li>
 *   <li>POST /api/v1/support/chat/fraud/alerts/{id}/review - Review alert (admin)</li>
 *   <li>GET /api/v1/support/chat/fraud/stats - Get fraud statistics</li>
 * </ul>
 */
@Tag(name = "Chat Support", description = "Customer support chat APIs")
@RestController
@RequestMapping("/api/v1/support/chat")
public class SupportChatController {

    private final ChatService chatService;
    private final AttachmentService attachmentService;

    public SupportChatController(ChatService chatService, AttachmentService attachmentService) {
        this.chatService = chatService;
        this.attachmentService = attachmentService;
    }

    /**
     * Create a new chat room.
     */
    @Operation(summary = "Create a new chat room", description = "Creates a new support chat room for a customer")
    @PostMapping("/rooms")
    public ResponseEntity<ChatRoomResponse> createRoom(
            @RequestBody CreateChatRoomRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        // Override customerId from JWT if available
        if (jwt != null && jwt.getClaimAsString("sub") != null) {
            try {
                Long customerIdFromToken = Long.parseLong(jwt.getClaimAsString("sub"));
                request = new CreateChatRoomRequest(
                    customerIdFromToken,
                    request.customerName(),
                    request.customerEmail(),
                    request.orderId(),
                    request.deliveryId(),
                    request.restaurantId(),
                    request.subject(),
                    request.initialMessage()
                );
            } catch (NumberFormatException e) {
                // Use provided customerId
            }
        }
        ChatRoomResponse response = chatService.createRoom(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get chat rooms for the authenticated customer.
     */
    @Operation(summary = "Get customer's chat rooms", description = "Returns all chat rooms for the authenticated customer")
    @GetMapping("/rooms")
    public ResponseEntity<List<ChatRoomResponse>> getRooms(
            @AuthenticationPrincipal Jwt jwt) {
        Long customerId = extractCustomerId(jwt);
        List<ChatRoomResponse> rooms = chatService.getRoomsByCustomer(customerId);
        return ResponseEntity.ok(rooms);
    }

    /**
     * Get chat room details by public ID.
     */
    @Operation(summary = "Get chat room details", description = "Returns room details by public ID")
    @GetMapping("/rooms/{publicId}")
    public ResponseEntity<ChatRoomResponse> getRoom(@PathVariable UUID publicId) {
        ChatRoomResponse response = chatService.getRoom(publicId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get messages for a chat room with pagination.
     */
    @Operation(summary = "Get chat messages", description = "Returns paginated messages for a room")
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<Page<ChatMessageResponse>> getMessages(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<ChatMessageResponse> messages = chatService.getMessages(roomId, page, size);
        return ResponseEntity.ok(messages);
    }

    /**
     * Send a message in a chat room.
     */
    @Operation(summary = "Send a message", description = "Sends a message in a chat room with fraud detection")
    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ChatMessageResponse> sendMessage(
            @PathVariable Long roomId,
            @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        // Override sender info from JWT
        if (jwt != null) {
            String senderName = jwt.getClaimAsString("name");
            String senderIdStr = jwt.getClaimAsString("sub");
            Long senderId = (senderIdStr != null) ? Long.parseLong(senderIdStr) : request.senderId();
            request = new SendMessageRequest(
                roomId,
                senderId,
                senderName != null ? senderName : request.senderName(),
                request.senderRole(),
                request.messageType(),
                request.content(),
                request.attachmentId()
            );
        } else {
            request = new SendMessageRequest(
                roomId,
                request.senderId(),
                request.senderName(),
                request.senderRole(),
                request.messageType(),
                request.content(),
                request.attachmentId()
            );
        }
        ChatMessageResponse response = chatService.sendMessage(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Upload an attachment for a message.
     */
    @Operation(summary = "Upload attachment", description = "Uploads a file attachment for a message")
    @PostMapping(value = "/rooms/{roomId}/messages/{messageId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttachmentResponse> uploadAttachment(
            @PathVariable Long roomId,
            @PathVariable Long messageId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        Long uploadedBy = extractCustomerId(jwt);
        AttachmentResponse response = chatService.uploadAttachment(roomId, messageId, uploadedBy, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get fraud alerts (admin only).
     */
    @Operation(summary = "Get fraud alerts", description = "Returns fraud alerts for admin review")
    @GetMapping("/fraud/alerts")
    public ResponseEntity<List<FraudAlertResponse>> getFraudAlerts(
            @RequestParam(required = false) Boolean reviewed) {
        List<FraudAlertResponse> alerts = chatService.getFraudAlerts(reviewed);
        return ResponseEntity.ok(alerts);
    }

    /**
     * Review a fraud alert (admin only).
     */
    @Operation(summary = "Review fraud alert", description = "Marks a fraud alert as reviewed with notes")
    @PostMapping("/fraud/alerts/{publicId}/review")
    public ResponseEntity<FraudAlertResponse> reviewAlert(
            @PathVariable UUID publicId,
            @RequestParam String reviewedBy,
            @RequestParam(required = false) String reviewNote,
            @RequestParam(required = false) String actionTaken) {
        FraudAlertResponse response = chatService.reviewFraudAlert(publicId, reviewedBy, reviewNote, actionTaken);
        return ResponseEntity.ok(response);
    }

    /**
     * Get fraud statistics for dashboard.
     */
    @Operation(summary = "Get fraud statistics", description = "Returns fraud statistics for admin dashboard")
    @GetMapping("/fraud/stats")
    public ResponseEntity<FraudStats> getFraudStats(
            @RequestParam(required = false) LocalDateTime since) {
        if (since == null) {
            since = LocalDateTime.now().minusDays(7);
        }
        FraudStats stats = chatService.getFraudStats(since);
        return ResponseEntity.ok(stats);
    }

    // ==================== Helper Methods ====================

    private Long extractCustomerId(Jwt jwt) {
        if (jwt != null && jwt.getClaimAsString("sub") != null) {
            try {
                return Long.parseLong(jwt.getClaimAsString("sub"));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}

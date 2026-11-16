package com.bhukkad.engagement.chat.api.controller;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.engagement.chat.api.dto.request.CreateChatSessionRequest;
import com.bhukkad.engagement.chat.api.dto.response.ChatMessageResponse;
import com.bhukkad.engagement.chat.api.dto.response.ChatResponse;
import com.bhukkad.engagement.chat.api.dto.response.ChatSessionResponse;
import com.bhukkad.engagement.chat.domain.entity.Attachment;
import com.bhukkad.engagement.chat.domain.entity.ChatMessage;
import com.bhukkad.engagement.chat.domain.entity.ChatSession;
import com.bhukkad.engagement.chat.domain.event.MessageSentEvent;
import com.bhukkad.engagement.chat.domain.repository.ChatMessageRepository;
import com.bhukkad.engagement.chat.domain.repository.ChatSessionRepository;
import com.bhukkad.engagement.fraud.domain.service.FraudDetectionEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST Controller for Chat Support System.
 * Manages chat sessions, messages, and multimedia attachments.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat Support", description = "Real-time chat support for restaurant operations")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final FraudDetectionEngine fraudEngine;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Create a new chat session.
     */
    @PostMapping("/sessions")
    @Operation(summary = "Create a new chat session")
    public ResponseEntity<ChatResponse<ChatSessionResponse>> createSession(
            @AuthenticationPrincipal TokenPrincipal principal,
            @Valid @RequestBody CreateChatSessionRequest request) {
        
        if (principal == null || principal.userId() == null) {
            return ResponseEntity.status(401)
                    .body(ChatResponse.error("UNAUTHORIZED", "Authentication required"));
        }

        ChatSession session = ChatSession.builder()
                .customerId(principal.userId())
                .orderId(request.orderId())
                .department(Objects.requireNonNull(request.department()))
                .subject(request.subject())
                .priority(ChatSession.Priority.NORMAL)
                .status(ChatSession.Status.OPEN)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        ChatSession saved = sessionRepository.save(session);
        return ResponseEntity.ok(ChatResponse.success(
                "Chat session created successfully", 
                ChatSessionResponse.fromEntity(saved, 0L)));
    }

    /**
     * Get chat session details.
     */
    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "Get chat session details")
    public ResponseEntity<ChatResponse<ChatSessionResponse>> getSession(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable String sessionId) {
        
        UUID sessionUuid = UUID.fromString(sessionId);
        ChatSession session = sessionRepository.findBySessionId(sessionUuid)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Chat session not found: " + sessionId));

        long messageCount = messageRepository.countBySessionId(sessionUuid);
        return ResponseEntity.ok(ChatResponse.success(
                ChatSessionResponse.fromEntity(session, messageCount)));
    }

    /**
     * Send a message in a chat session.
     */
    @PostMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "Send a message in a chat session")
    public ResponseEntity<ChatResponse<ChatMessageResponse>> sendMessage(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable String sessionId,
            @RequestPart("content") String content,
            @RequestPart(value = "attachments", required = false) MultipartFile[] attachments) throws IOException {
        
        if (principal == null || principal.userId() == null) {
            return ResponseEntity.status(401)
                    .body(ChatResponse.error("UNAUTHORIZED", "Authentication required"));
        }

        UUID sessionUuid = UUID.fromString(sessionId);
        ChatSession session = sessionRepository.findBySessionId(sessionUuid)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Chat session not found: " + sessionId));

        // Create message
        ChatMessage message = ChatMessage.builder()
                .sessionId(sessionUuid)
                .senderId(principal.userId())
                .senderRole(ChatMessage.SenderRole.CUSTOMER)
                .messageType(ChatMessage.MessageType.TEXT)
                .content(content)
                .createdAt(LocalDateTime.now())
                .isFraudFlagged(false)
                .build();

        ChatMessage savedMessage = messageRepository.save(message);

        // Publish event for async fraud processing (decouples delivery from analysis)
        List<Attachment> attachedFiles = new ArrayList<>();
        if (attachments != null && attachments.length > 0) {
            log.debug("Processing {} attachments for fraud analysis", attachments.length);
            // In production, attachments would be stored in S3 and the Attachment entity created
        }

        eventPublisher.publishEvent(new MessageSentEvent(
            this, savedMessage.getMessageId(), session.getSessionId(),
            session, savedMessage, attachedFiles));

        ChatMessageResponse response = ChatMessageResponse.fromEntity(savedMessage, Collections.emptyList());
        return ResponseEntity.ok(ChatResponse.success("Message sent successfully", response));
    }

    /**
     * Upload and analyze an attachment.
     */
    @PostMapping("/messages/{messageId}/attachments")
    @Operation(summary = "Upload attachment for a message")
    public ResponseEntity<ChatResponse<Map<String, Object>>> uploadAttachment(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable String messageId,
            @RequestPart("file") MultipartFile file) throws IOException {
        
        if (principal == null || principal.userId() == null) {
            return ResponseEntity.status(401)
                    .body(ChatResponse.error("UNAUTHORIZED", "Authentication required"));
        }

        UUID messageUuid = UUID.fromString(messageId);
        ChatMessage message = messageRepository.findByMessageId(messageUuid)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Chat message not found with id: " + messageId));

        // Create Attachment entity for validation
        Attachment attachment = Attachment.builder()
                .messageId(messageUuid)
                .fileName(file.getOriginalFilename())
                .fileSizeBytes(file.getSize())
                .mimeType(file.getContentType())
                .contentType(Attachment.ContentType.ORIGINAL)
                .storageProvider(Attachment.StorageProvider.LOCAL)
                .storageKey("temp-" + UUID.randomUUID())
                .createdAt(LocalDateTime.now())
                .build();

        // Validate attachment
        var validationResult = fraudEngine.validateAttachment(attachment);
        
        Map<String, Object> result = new HashMap<>();
        result.put("valid", validationResult.isValid());
        result.put("attachmentId", UUID.randomUUID().toString());
        result.put("fileName", file.getOriginalFilename());
        result.put("fileSizeBytes", file.getSize());
        result.put("mimeType", file.getContentType());
        result.put("isDuplicate", validationResult.isDuplicate());
        
        if (!validationResult.issues().isEmpty()) {
            result.put("validationIssues", validationResult.issues());
        }

        return ResponseEntity.ok(ChatResponse.success("Attachment validated", result));
    }

    /**
     * Get paginated messages from a session.
     */
    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "Get paginated messages from a chat session")
    public ResponseEntity<ChatResponse<List<ChatMessageResponse>>> getMessages(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        UUID sessionUuid = UUID.fromString(sessionId);
        org.springframework.data.domain.Pageable pageable = 
                org.springframework.data.domain.PageRequest.of(page, Math.min(size, 100));
        
        var messagePage = messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionUuid, pageable);
        List<ChatMessageResponse> messages = messagePage.getContent().stream()
                .map(m -> ChatMessageResponse.fromEntity(m, Collections.emptyList()))
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ChatResponse.success(messages));
    }

    /**
     * Close a chat session.
     */
    @PostMapping("/sessions/{sessionId}/close")
    @Operation(summary = "Close a chat session")
    public ResponseEntity<ChatResponse<Void>> closeSession(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable String sessionId,
            @RequestParam String reason) {
        
        UUID sessionUuid = UUID.fromString(sessionId);
        ChatSession session = sessionRepository.findBySessionId(sessionUuid)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Chat session not found: " + sessionId));

        session.setStatus(ChatSession.Status.CLOSED);
        session.setClosedAt(LocalDateTime.now());
        sessionRepository.save(session);

        return ResponseEntity.ok(ChatResponse.success("Session closed", null));
    }
}
package com.bhukkad.engagement.chat.api.controller;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.engagement.chat.ai.SupportBotService;
import com.bhukkad.engagement.chat.domain.entity.ChatMessage;
import com.bhukkad.engagement.chat.domain.entity.ChatSession;
import com.bhukkad.engagement.chat.domain.repository.ChatMessageRepository;
import com.bhukkad.engagement.chat.domain.repository.ChatSessionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for AI Support Bot streaming chat.
 * Provides SSE endpoint for real-time bot responses.
 * <p>
 * Guarded by feature flag {@code app.chatbot.enabled} (default: false).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/live/chatbot")
@Tag(name = "AI Support Bot", description = "Conversational AI support bot with streaming responses")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.chatbot.enabled", havingValue = "true", matchIfMissing = false)
public class ChatBotController {

    private final SupportBotService botService;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    /**
     * Stream a conversation with the AI support bot.
     * Returns Server-Sent Events for real-time response streaming.
     */
    @GetMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream chatbot response for a session")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<StreamingResponseBody> streamBotResponse(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable String sessionId,
            @RequestParam String message) {

        if (principal == null || principal.userId() == null) {
            return ResponseEntity.status(401)
                    .body(outputStream -> outputStream.write(
                            "event: error\ndata: {\"error\":\"Authentication required\"}\n\n".getBytes()));
        }

        UUID sessionUuid = UUID.fromString(sessionId);
        ChatSession session = sessionRepository.findBySessionId(sessionUuid)
                .orElseThrow(() -> new IllegalArgumentException("Chat session not found: " + sessionId));

        // Verify session belongs to customer
        if (!session.getCustomerId().equals(principal.userId())) {
            return ResponseEntity.status(403)
                    .body(outputStream -> outputStream.write(
                            "event: error\ndata: {\"error\":\"Not authorized for this session\"}\n\n".getBytes()));
        }

        // Get conversation history
        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionUuid);

        log.info("BOT_STREAM_START | sessionId={} | customerId={} | messageLength={}",
                sessionId, principal.userId(), message.length());

        StreamingResponseBody stream = botService.createStreamingResponse(
                sessionId, principal.userId(), message, history);

        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stream);
    }

    /**
     * Non-streaming endpoint for simple request-response (fallback).
     */
    @PostMapping("/sessions/{sessionId}/message")
    @Operation(summary = "Send message to chatbot (non-streaming)")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<BotResponse> sendMessage(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable String sessionId,
            @RequestBody BotRequest request) {

        if (principal == null || principal.userId() == null) {
            return ResponseEntity.status(401).body(new BotResponse(false, "Authentication required", null));
        }

        UUID sessionUuid = UUID.fromString(sessionId);
        ChatSession session = sessionRepository.findBySessionId(sessionUuid)
                .orElseThrow(() -> new IllegalArgumentException("Chat session not found: " + sessionId));

        if (!session.getCustomerId().equals(principal.userId())) {
            return ResponseEntity.status(403).body(new BotResponse(false, "Not authorized for this session", null));
        }

        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionUuid);

        // For non-streaming, we collect the response
        StringBuilder responseBuilder = new StringBuilder();
        botService.processMessage(sessionId, principal.userId(), request.message(), history, event -> {
            // Parse SSE events and extract message content
            if (event.contains("event: message")) {
                int dataIdx = event.indexOf("data: ");
                if (dataIdx >= 0) {
                    String json = event.substring(dataIdx + 6).trim();
                    try {
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        var node = mapper.readTree(json);
                        if (node.has("content")) {
                            responseBuilder.append(node.get("content").asText());
                        }
                    } catch (Exception ignored) {}
                }
            }
        });

        return ResponseEntity.ok(new BotResponse(true, "OK", responseBuilder.toString()));
    }

    public record BotRequest(String message) {}
    public record BotResponse(boolean success, String message, String content) {}
}
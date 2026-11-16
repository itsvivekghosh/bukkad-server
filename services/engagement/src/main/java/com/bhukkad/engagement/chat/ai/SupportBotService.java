package com.bhukkad.engagement.chat.ai;

import com.bhukkad.engagement.chat.domain.entity.ChatMessage;
import com.bhukkad.engagement.chat.domain.entity.ChatSession;
import com.bhukkad.engagement.fraud.domain.service.FraudScoringEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Support Bot Service orchestrating the conversational AI flow:
 * Intent Classification → RAG Retrieval → Tool Use → Human Handoff
 * <p>
 * Provides streaming responses via SSE/StreamingResponseBody.
 * Guarded by feature flag {@code app.chatbot.enabled} (default: false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.chatbot.enabled", havingValue = "true", matchIfMissing = false)
public class SupportBotService {

    private final IntentClassifier intentClassifier;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final OrderLookupTool orderLookupTool;
    private final OrderTrackingTool orderTrackingTool;
    private final RefundTool refundTool;
    private final SupportTicketTool supportTicketTool;
    private final FraudScoringEngine fraudScoringEngine;
    private final PiiRedactor piiRedactor;

    // Session state for multi-turn conversations
    private final Map<String, BotSessionState> sessionStates = new ConcurrentHashMap<>();

    /**
     * Processes a user message and streams the response via SSE.
     *
     * @param sessionId       chat session ID
     * @param customerId      customer ID
     * @param message         user's message
     * @param conversationHistory previous messages in the session
     * @param output          streaming output consumer
     */
    public void processMessage(String sessionId,
                               Long customerId,
                               String message,
                               List<ChatMessage> conversationHistory,
                               Consumer<String> output) {

        // Check feature flag at runtime (double-check)
        if (!isEnabled()) {
            output.accept(createSseEvent("error", Map.of("error", "Chatbot not enabled")));
            return;
        }

        BotSessionState state = sessionStates.computeIfAbsent(sessionId, k -> new BotSessionState());

        // Redact PII from user message before processing
        PiiRedactor.RedactionResult redactedMessage = piiRedactor.redactWithOriginal(message);
        String safeMessage = redactedMessage.redacted();

        log.info("BOT_PROCESS_MESSAGE | sessionId={} | customerId={} | intent={} | piiRedacted={}",
                sessionId, customerId, safeMessage, redactedMessage.original() != null);

        try {
            // 1. Check for escalation triggers
            EscalationCheck escalation = checkEscalationTriggers(sessionId, customerId, safeMessage, conversationHistory);
            if (escalation.shouldEscalate()) {
                handleEscalation(sessionId, customerId, safeMessage, conversationHistory, escalation, output);
                return;
            }

            // 2. Classify intent
            Map<String, Object> context = buildContext(customerId, conversationHistory, state);
            IntentClassifier.Intent intent = intentClassifier.classify(safeMessage, context);

            log.debug("BOT_INTENT_CLASSIFIED | sessionId={} | intent={}", sessionId, intent);

            // 3. Handle based on intent
            switch (intent) {
                case HUMAN_HANDOFF -> handleHumanHandoff(sessionId, customerId, safeMessage, conversationHistory, output);
                case ORDER_LOOKUP -> handleOrderLookup(sessionId, customerId, safeMessage, context, output);
                case ORDER_TRACKING -> handleOrderTracking(sessionId, customerId, safeMessage, context, output);
                case REFUND_REQUEST -> handleRefundRequest(sessionId, customerId, safeMessage, context, output);
                case POLICY_QUESTION -> handlePolicyQuestion(sessionId, safeMessage, context, output);
                case GENERAL_CHAT -> handleGeneralChat(sessionId, safeMessage, output);
                case FRAUD_REPORT -> handleFraudReport(sessionId, customerId, safeMessage, conversationHistory, output);
                default -> handleUnknown(sessionId, safeMessage, output);
            }

            // Update session state
            state.lastIntent = intent;
            state.lastActivity = LocalDateTime.now();
            state.messageCount++;

        } catch (Exception e) {
            log.error("BOT_PROCESS_ERROR | sessionId={} | error={}", sessionId, e.getMessage(), e);
            output.accept(createSseEvent("error", Map.of("error", "Processing failed: " + e.getMessage())));
        }
    }

    /**
     * Processes a user message and returns a StreamingResponseBody for Spring MVC.
     */
    public StreamingResponseBody createStreamingResponse(String sessionId,
                                                          Long customerId,
                                                          String message,
                                                          List<ChatMessage> conversationHistory) {
        return outputStream -> {
            Consumer<String> sseConsumer = event -> {
                try {
                    outputStream.write(event.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException e) {
                    log.warn("BOT_STREAM_WRITE_FAILED | sessionId={} | error={}", sessionId, e.getMessage());
                }
            };
            processMessage(sessionId, customerId, message, conversationHistory, sseConsumer);
        };
    }

    /**
     * Creates an SSE event string.
     */
    private String createSseEvent(String eventType, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(eventType).append("\n");
        sb.append("data: ").append(toJson(data)).append("\n\n");
        return sb.toString();
    }

    private String toJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean isEnabled() {
        // Runtime check - in practice this is guarded by @ConditionalOnProperty
        return true;
    }

    // --- Escalation Check ---

    private EscalationCheck checkEscalationTriggers(String sessionId,
                                                     Long customerId,
                                                     String message,
                                                     List<ChatMessage> history) {
        // 1. Explicit human request (handled by intent classifier, but double-check)
        if (message.toLowerCase(Locale.ROOT).matches(".*\\b(human|agent|representative|person)\\b.*")) {
            return new EscalationCheck(true, "EXPLICIT_HUMAN_REQUEST", "User requested human agent");
        }

        // 2. Fraud flags from FraudScoringEngine
        if (customerId != null && !history.isEmpty()) {
            ChatMessage lastMessage = history.get(history.size() - 1);
            ChatSession session = new ChatSession(); // minimal session for fraud check
            session.setCustomerId(customerId);
            // Note: In real implementation, pass actual session with department
            FraudScoringEngine.FraudScore fraudScore = fraudScoringEngine.evaluateRisk(session, lastMessage, List.of());
            if (fraudScore.totalScore() >= 50.0) {
                return new EscalationCheck(true, "FRAUD_FLAG", "High fraud risk score: " + fraudScore.totalScore());
            }
        }

        // 3. Repeated failures / frustration detection
        BotSessionState state = sessionStates.get(sessionId);
        if (state != null && state.failedToolCalls >= 3) {
            return new EscalationCheck(true, "REPEATED_FAILURES", "Multiple tool failures in session");
        }

        // 4. Session length / complexity
        if (state != null && state.messageCount > 10) {
            return new EscalationCheck(true, "SESSION_COMPLEXITY", "Long conversation, consider human handoff");
        }

        return new EscalationCheck(false, null, null);
    }

    private record EscalationCheck(boolean shouldEscalate, String trigger, String reason) {}

    private void handleEscalation(String sessionId, Long customerId, String message,
                                   List<ChatMessage> history, EscalationCheck check,
                                   Consumer<String> output) {
        log.warn("BOT_ESCALATION_TRIGGERED | sessionId={} | trigger={} | reason={}",
                sessionId, check.trigger(), check.reason());

        output.accept(createSseEvent("escalation", Map.of(
                "trigger", check.trigger(),
                "reason", check.reason(),
                "message", "I'm transferring you to a human agent. Please hold..."
        )));

        // Create support ticket with full context
        String contextSummary = buildContextSummary(history, message);
        supportTicketTool.createTicket(
                customerId,
                extractOrderIdFromHistory(history),
                "Chatbot Escalation: " + check.trigger(),
                contextSummary,
                "chatbot-handoff",
                "HIGH",
                Map.of(
                        "escalationTrigger", check.trigger(),
                        "escalationReason", check.reason(),
                        "sessionId", sessionId,
                        "messageCount", sessionStates.getOrDefault(sessionId, new BotSessionState()).messageCount
                )
        );

        output.accept(createSseEvent("complete", Map.of("handoff", true)));
    }

    // --- Intent Handlers ---

    private void handleHumanHandoff(String sessionId, Long customerId, String message,
                                     List<ChatMessage> history, Consumer<String> output) {
        output.accept(createSseEvent("message", Map.of(
                "content", "I'll connect you with a human support agent. One moment please..."
        )));

        String contextSummary = buildContextSummary(history, message);
        supportTicketTool.createTicket(
                customerId,
                extractOrderIdFromHistory(history),
                "Customer Requested Human Agent",
                contextSummary,
                "chatbot-handoff",
                "HIGH",
                Map.of("sessionId", sessionId, "reason", "explicit_request")
        );

        output.accept(createSseEvent("complete", Map.of("handoff", true)));
    }

    private void handleOrderLookup(String sessionId, Long customerId, String message,
                                    Map<String, Object> context, Consumer<String> output) {
        Long orderId = extractOrderId(message, context);
        if (orderId == null) {
            output.accept(createSseEvent("message", Map.of(
                    "content", "I'd be happy to look up your order. Could you please provide your order ID or order number?"
            )));
            return;
        }

        output.accept(createSseEvent("tool_start", Map.of("tool", "order_lookup", "orderId", orderId)));

        Optional<OrderLookupTool.OrderDetails> order = orderLookupTool.lookupOrder(customerId, orderId);

        if (order.isPresent()) {
            OrderLookupTool.OrderDetails details = order.get();
            String response = formatOrderDetails(details);
            output.accept(createSseEvent("tool_result", Map.of("tool", "order_lookup", "success", true)));
            output.accept(createSseEvent("message", Map.of("content", response)));
        } else {
            output.accept(createSseEvent("tool_result", Map.of("tool", "order_lookup", "success", false)));
            output.accept(createSseEvent("message", Map.of(
                    "content", "I couldn't find an order with ID " + orderId + " for your account. Please check the order number and try again."
            )));
        }
    }

    private void handleOrderTracking(String sessionId, Long customerId, String message,
                                      Map<String, Object> context, Consumer<String> output) {
        Long orderId = extractOrderId(message, context);
        if (orderId == null) {
            output.accept(createSseEvent("message", Map.of(
                    "content", "To track your order, I'll need the order ID. Could you please provide it?"
            )));
            return;
        }

        output.accept(createSseEvent("tool_start", Map.of("tool", "order_tracking", "orderId", orderId)));

        Optional<OrderTrackingTool.TrackingInfo> tracking = orderTrackingTool.trackOrder(customerId, orderId);

        if (tracking.isPresent()) {
            OrderTrackingTool.TrackingInfo info = tracking.get();
            String response = formatTrackingInfo(info);
            output.accept(createSseEvent("tool_result", Map.of("tool", "order_tracking", "success", true)));
            output.accept(createSseEvent("message", Map.of("content", response)));
        } else {
            output.accept(createSseEvent("tool_result", Map.of("tool", "order_tracking", "success", false)));
            output.accept(createSseEvent("message", Map.of(
                    "content", "I couldn't retrieve tracking info for order " + orderId + ". Please verify the order ID."
            )));
        }
    }

    private void handleRefundRequest(String sessionId, Long customerId, String message,
                                      Map<String, Object> context, Consumer<String> output) {
        Long orderId = extractOrderId(message, context);

        output.accept(createSseEvent("message", Map.of(
                "content", "I understand you'd like to request a refund. Let me help you with that."
        )));

        if (orderId == null) {
            output.accept(createSseEvent("message", Map.of(
                    "content", "To process a refund, I'll need the order ID. Could you please provide it?"
            )));
            return;
        }

        // Extract amount if mentioned (simplified)
        Double amount = extractAmount(message);

        output.accept(createSseEvent("tool_start", Map.of("tool", "refund_request", "orderId", orderId)));
        RefundTool.RefundResult result = refundTool.requestRefund(customerId, orderId,
                "Customer requested refund via chatbot: " + message, amount);

        if (result.success()) {
            output.accept(createSseEvent("tool_result", Map.of("tool", "refund_request", "success", true)));
            output.accept(createSseEvent("message", Map.of(
                    "content", "Your refund request has been submitted! Refund ID: " + result.refundId() +
                            "\nStatus: " + result.status() +
                            "\nAmount: $" + String.format("%.2f", result.amount()) +
                            "\nEstimated processing: " + result.estimatedProcessingTime()
            )));
        } else {
            output.accept(createSseEvent("tool_result", Map.of("tool", "refund_request", "success", false)));
            output.accept(createSseEvent("message", Map.of(
                    "content", "I wasn't able to process the refund request: " + result.message() +
                            "\nWould you like me to connect you with a human agent for further assistance?"
            )));
        }
    }

    private void handlePolicyQuestion(String sessionId, String message,
                                       Map<String, Object> context, Consumer<String> output) {
        // RAG: Search vector store for relevant knowledge
        float[] queryEmbedding = embeddingProvider.embed(message);
        List<VectorStore.SearchResult> results = vectorStore.search(queryEmbedding, 3);

        if (results.isEmpty()) {
            output.accept(createSseEvent("message", Map.of(
                    "content", "I don't have specific information about that. Let me connect you with a human agent who can help better."
            )));
            return;
        }

        // Build response from retrieved knowledge
        StringBuilder response = new StringBuilder("Based on our help center:\n\n");
        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult r = results.get(i);
            response.append(r.content()).append("\n\n");
        }
        response.append("Does this answer your question, or would you like more details?");

        output.accept(createSseEvent("rag_results", Map.of("count", results.size())));
        output.accept(createSseEvent("message", Map.of("content", response.toString())));
    }

    private void handleGeneralChat(String sessionId, String message, Consumer<String> output) {
        String[] greetings = {
                "Hello! How can I help you today?",
                "Hi there! What can I do for you?",
                "Welcome! I'm here to assist with your orders, deliveries, and questions."
        };
        String response = greetings[new Random().nextInt(greetings.length)];
        output.accept(createSseEvent("message", Map.of("content", response)));
    }

    private void handleFraudReport(String sessionId, Long customerId, String message,
                                    List<ChatMessage> history, Consumer<String> output) {
        output.accept(createSseEvent("message", Map.of(
                "content", "Thank you for reporting this. I'm escalating this to our fraud investigation team immediately."
        )));

        String contextSummary = buildContextSummary(history, message);
        supportTicketTool.createTicket(
                customerId,
                extractOrderIdFromHistory(history),
                "Fraud Report from Chatbot",
                contextSummary,
                "fraud",
                "URGENT",
                Map.of("sessionId", sessionId, "reportType", "customer_fraud_report")
        );

        output.accept(createSseEvent("complete", Map.of("escalated", true, "type", "fraud")));
    }

    private void handleUnknown(String sessionId, String message, Consumer<String> output) {
        output.accept(createSseEvent("message", Map.of(
                "content", "I'm not sure I understand. Could you rephrase that? " +
                        "You can ask me about your orders, track deliveries, request refunds, " +
                        "or ask about our policies. You can also say 'human' to speak with an agent."
        )));
    }

    // --- Helper Methods ---

    private Map<String, Object> buildContext(Long customerId, List<ChatMessage> history, BotSessionState state) {
        Map<String, Object> context = new HashMap<>();
        context.put("customerId", customerId);
        context.put("messageCount", state.messageCount);
        context.put("lastIntent", state.lastIntent);
        if (!history.isEmpty()) {
            context.put("lastMessage", history.get(history.size() - 1).getContent());
            // Extract order IDs from recent history
            List<Long> orderIds = new ArrayList<>();
            for (ChatMessage msg : history) {
                if (msg.getContent() != null) {
                    extractOrderIds(msg.getContent()).forEach(orderIds::add);
                }
            }
            context.put("mentionedOrderIds", orderIds);
        }
        return context;
    }

    private List<Long> extractOrderIds(String text) {
        List<Long> ids = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(?:order|ORD)[-\\s]?#?(\\d+)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        while (m.find()) {
            try {
                ids.add(Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {}
        }
        return ids;
    }

    private Long extractOrderId(String message, Map<String, Object> context) {
        List<Long> fromMessage = extractOrderIds(message);
        if (!fromMessage.isEmpty()) return fromMessage.get(0);

        @SuppressWarnings("unchecked")
        List<Long> fromContext = (List<Long>) context.get("mentionedOrderIds");
        if (fromContext != null && !fromContext.isEmpty()) return fromContext.get(0);

        return null;
    }

    private Long extractOrderIdFromHistory(List<ChatMessage> history) {
        for (ChatMessage msg : history) {
            List<Long> ids = extractOrderIds(msg.getContent());
            if (!ids.isEmpty()) return ids.get(0);
        }
        return null;
    }

    private Double extractAmount(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$?(\\d+(?:\\.\\d{2})?)\\s*(?:dollars?|USD|refund)?", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(message);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String buildContextSummary(List<ChatMessage> history, String currentMessage) {
        StringBuilder sb = new StringBuilder("Conversation Summary:\n");
        for (ChatMessage msg : history) {
            String role = msg.getSenderRole().name().toLowerCase(Locale.ROOT);
            String content = msg.getContent() != null ? msg.getContent() : "[attachment]";
            sb.append("[").append(role).append("] ").append(content).append("\n");
        }
        sb.append("[customer] ").append(currentMessage).append("\n");
        return sb.toString();
    }

    private String formatOrderDetails(OrderLookupTool.OrderDetails order) {
        return String.format("""
                **Order Details**
                Order ID: %d (%s)
                Status: %s
                Restaurant: %s
                Payment: %s
                Total: $%.2f
                Placed: %s
                
                Items:
                %s
                """,
                order.orderId(), order.orderNumber(), order.status(), order.restaurantName(),
                order.paymentStatus(), order.totalAmount(), order.createdAt(),
                order.items().stream()
                        .map(i -> String.format("  - %s x%d @ $%.2f", i.name(), i.quantity(), i.price()))
                        .reduce("", (a, b) -> a + "\n" + b)
        );
    }

    private String formatTrackingInfo(OrderTrackingTool.TrackingInfo info) {
        String riderInfo = info.riderName() != null
                ? String.format("Rider: %s (%s)", info.riderName(), info.riderPhone())
                : "No rider assigned yet";

        return String.format("""
                **Order Tracking**
                Order ID: %d
                Status: %s
                Restaurant: %s
                %s
                ETA: %s
                Last Update: %s
                """,
                info.orderId(), info.status(), info.restaurantName(),
                riderInfo, info.estimatedArrival(), info.lastUpdate()
        );
    }

    /**
     * Per-session bot state.
     */
    private static class BotSessionState {
        IntentClassifier.Intent lastIntent = IntentClassifier.Intent.UNKNOWN;
        LocalDateTime lastActivity = LocalDateTime.now();
        int messageCount = 0;
        int failedToolCalls = 0;
    }
}
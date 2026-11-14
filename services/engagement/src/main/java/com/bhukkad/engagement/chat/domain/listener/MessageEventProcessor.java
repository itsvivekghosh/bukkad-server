package com.bhukkad.engagement.chat.domain.listener;

import com.bhukkad.engagement.chat.api.dto.response.ChatMessageResponse;
import com.bhukkad.engagement.chat.domain.entity.Attachment;
import com.bhukkad.engagement.chat.domain.entity.ChatMessage;
import com.bhukkad.engagement.chat.domain.entity.ChatSession;
import com.bhukkad.engagement.chat.domain.event.MessageSentEvent;
import com.bhukkad.engagement.chat.domain.repository.ChatMessageRepository;
import com.bhukkad.engagement.chat.domain.repository.ChatSessionRepository;
import com.bhukkad.engagement.fraud.domain.entity.FraudCase;
import com.bhukkad.engagement.fraud.domain.service.FraudDetectionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Async event processor for chat messages.
 * Handles fraud detection and session escalation in background thread.
 * 
 * <p>Improves throughput by decoupling message delivery from fraud analysis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageEventProcessor {

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final FraudDetectionEngine fraudEngine;

    /**
     * Handles MessageSentEvent asynchronously.
     * Fraud detection runs in background thread, not blocking the HTTP response.
     */
    @EventListener
    @Async
    @Transactional
    public void handleMessageSent(MessageSentEvent event) {
        UUID messageId = event.getMessageId();
        ChatSession session = event.getSession();
        ChatMessage message = event.getMessage();
        List<Attachment> attachments = event.getAttachments();

        log.debug("Processing fraud detection for message {} in session {}", 
                  messageId, session.getSessionId());

        try {
            // Evaluate fraud risk in background
            FraudDetectionEngine.FraudScore riskScore = fraudEngine.evaluateRisk(session, message, attachments);
            
            if (riskScore.totalScore() >= 30.0) {
                // Create fraud case if high risk
                FraudCase fraudCase = fraudEngine.createFraudCase(session, message, null, riskScore);
                if (fraudCase != null) {
                    // Update message to flag it
                    message.setFraudFlagged(true);
                    message.setFraudReason("High fraud risk score: " + riskScore.totalScore());
                    messageRepository.save(message);
                    
                    // Escalate session
                    session.setStatus(ChatSession.Status.ESCALATED);
                    sessionRepository.save(session);
                    
                    log.warn("Message {} flagged as fraudulent (score={}), session {} escalated", 
                             messageId, riskScore.totalScore(), session.getSessionId());
                }
            }
        } catch (Exception ex) {
            log.error("Fraud detection failed for message {}: {}", messageId, ex.getMessage());
        }
    }
}

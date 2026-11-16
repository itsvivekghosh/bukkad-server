package com.bhukkad.engagement.chat.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Chat message entity representing individual messages within a session.
 */
@Entity(name = "ChatMessage")
@Table(name = "chat_messages",
       indexes = {
           @Index(name = "idx_chat_messages_session_id", columnList = "session_id"),
           @Index(name = "idx_chat_messages_created_at", columnList = "created_at DESC"),
           @Index(name = "idx_chat_messages_sender_id", columnList = "sender_id")
       })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true)
    private UUID messageId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 20)
    private SenderRole senderRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 20)
    private MessageType messageType;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "reply_to_message_id")
    private UUID replyToMessageId;

    @Column(name = "sentiment_score")
    private Short sentimentScore;

    @Column(name = "is_fraud_flagged", nullable = false)
    private boolean isFraudFlagged;

    @Column(name = "fraud_reason", columnDefinition = "TEXT")
    private String fraudReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public enum SenderRole {
        CUSTOMER, SUPPORT_AGENT, SYSTEM
    }

    public enum MessageType {
        TEXT, IMAGE, FILE, SYSTEM, ACTION
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (messageId == null) {
            messageId = UUID.randomUUID();
        }
    }
}
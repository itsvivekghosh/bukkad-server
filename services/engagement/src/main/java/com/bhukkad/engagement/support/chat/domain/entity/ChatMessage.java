package com.bhukkad.engagement.support.chat.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ChatMessage represents a single message within a chat room.
 *
 * <p>Messages can be text-based or contain media attachments. Each message
 * carries fraud-related metadata computed by the FraudDetectionEngine:</p>
 *
 * <ul>
 *   <li>{@code fraudScore}: risk score for this specific message (0-100)</li>
 *   <li>{@code fraudReasons}: comma-separated reasons for flagging</li>
 *   <li>{@code duplicateHash}: hash of message content for duplicate detection</li>
 *   <li>{@code isAutoFlagged}: whether the system auto-flagged this message</li>
 * </ul>
 *
 * <p>Anti-fraud checks performed on each message:</p>
 * <ul>
 *   <li>Duplicate message detection (same content sent multiple times)</li>
 *   <li>Keyword detection (refund scam keywords, threat language)</li>
 *   <li>Velocity checking (messages per minute per user)</li>
 *   <li>Image metadata analysis (EXIF data tampering detection)</li>
 *   <li>Order validation (referenced order exists and belongs to customer)</li>
 * </ul>
 */
@Entity(name = "SupportChatMessage")
@Table(name = "chat_messages", indexes = {
    @Index(name = "idx_chat_message_room", columnList = "room_id"),
    @Index(name = "idx_chat_message_created", columnList = "created_at"),
    @Index(name = "idx_chat_message_sender", columnList = "sender_id, created_at DESC"),
    @Index(name = "idx_chat_message_fraud_score", columnList = "fraud_score DESC"),
    @Index(name = "idx_chat_message_duplicate_hash", columnList = "duplicate_hash"),
    @Index(name = "idx_chat_message_auto_flagged", columnList = "is_auto_flagged")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", unique = true, nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID publicId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "sender_name", length = 200, nullable = false)
    private String senderName;

    @Column(name = "sender_role", length = 20, nullable = false)
    private String senderRole; // CUSTOMER, AGENT, SYSTEM

    @Column(name = "message_type", length = 20, nullable = false)
    private String messageType; // TEXT, IMAGE, DOCUMENT, SYSTEM

    @Column(name = "content", length = 4000)
    private String content;

    @Column(name = "attachment_id")
    private Long attachmentId;

    @Column(name = "duplicate_hash", length = 64)
    private String duplicateHash;

    @Column(name = "fraud_score", nullable = false)
    @Builder.Default
    private Integer fraudScore = 0;

    @Column(name = "fraud_reasons", length = 1000)
    private String fraudReasons;

    @Column(name = "is_auto_flagged", nullable = false)
    @Builder.Default
    private Boolean isAutoFlagged = false;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (publicId == null) {
            publicId = java.util.UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = java.time.LocalDateTime.now();
        }
        if (fraudScore == null) {
            fraudScore = 0;
        }
        if (isAutoFlagged == null) {
            isAutoFlagged = false;
        }
        if (isRead == null) {
            isRead = false;
        }
    }
}

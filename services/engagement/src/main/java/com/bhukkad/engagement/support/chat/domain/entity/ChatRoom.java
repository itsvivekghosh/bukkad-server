package com.bhukkad.engagement.support.chat.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ChatRoom represents a conversation thread between a customer and support agent.
 *
 * <p>Each room is tied to an optional order and/or delivery context, enabling
 * context-aware support. Rooms can be in one of several lifecycle states:
 * OPEN, ASSIGNED, RESOLVED, ESCALATED, or CLOSED.</p>
 *
 * <p>Anti-fraud integration: each room carries a fraudScore (0-100) that is
 * updated by the FraudDetectionEngine as messages arrive. Rooms with
 * score &gt;= 70 are auto-flagged for admin review.</p>
 */
@Entity
@Table(name = "chat_rooms", indexes = {
    @Index(name = "idx_chat_room_customer", columnList = "customer_id"),
    @Index(name = "idx_chat_room_status", columnList = "status"),
    @Index(name = "idx_chat_room_created", columnList = "created_at DESC"),
    @Index(name = "idx_chat_room_order", columnList = "order_id"),
    @Index(name = "idx_chat_room_fraud_score", columnList = "fraud_score DESC")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Public-facing UUID for external API references (prevents ID enumeration).
     */
    @Column(name = "public_id", unique = true, nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID publicId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "agent_id", length = 100)
    private String agentId;

    @Column(name = "agent_name", length = 200)
    private String agentName;

    /**
     * Associated order, if the chat is about a specific order.
     */
    @Column(name = "order_id")
    private Long orderId;

    /**
     * Associated delivery, if the chat is about delivery status.
     */
    @Column(name = "delivery_id")
    private Long deliveryId;

    @Column(name = "restaurant_id")
    private Long restaurantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ChatRoomStatus status = ChatRoomStatus.OPEN;

    @Column(name = "subject", length = 500)
    private String subject;

    /**
     * Fraud risk score (0-100). Updated by FraudDetectionEngine.
     * Scores >= 70 trigger automatic flagging for admin review.
     */
    @Column(name = "fraud_score", nullable = false)
    @Builder.Default
    private Integer fraudScore = 0;

    @Column(name = "fraud_flagged", nullable = false)
    @Builder.Default
    private Boolean fraudFlagged = false;

    @Column(name = "fraud_reasons", length = 1000)
    private String fraudReasons;

    @Column(name = "total_messages", nullable = false)
    @Builder.Default
    private Integer totalMessages = 0;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onCreate() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ChatRoomStatus.OPEN;
        }
        if (totalMessages == null) {
            totalMessages = 0;
        }
        if (fraudScore == null) {
            fraudScore = 0;
        }
        if (fraudFlagged == null) {
            fraudFlagged = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum ChatRoomStatus {
        OPEN, ASSIGNED, RESOLVED, ESCALATED, CLOSED
    }
}

package com.bhukkad.engagement.chat.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Chat session entity representing a conversation thread.
 * Used for order management, delivery troubleshooting, and customer service.
 */
@Entity
@Table(name = "chat_sessions",
       indexes = {
           @Index(name = "idx_chat_sessions_customer_id", columnList = "customer_id"),
           @Index(name = "idx_chat_sessions_order_id", columnList = "order_id"),
           @Index(name = "idx_chat_sessions_status", columnList = "status"),
           @Index(name = "idx_chat_sessions_created_at", columnList = "created_at DESC")
       })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@Builder
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "department", nullable = false, length = 50)
    private Department department;

    @Column(name = "subject", length = 200)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private Priority priority;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "satisfaction_rating")
    private Short satisfactionRating;

    @Column(name = "satisfaction_comment", columnDefinition = "TEXT")
    private String satisfactionComment;

    // Enums for department, status, and priority
    public enum Department {
        ORDER("Order Management"),
        DELIVERY("Delivery Tracking"),
        GENERAL("General Service"),
        PAYMENT("Payment Issues");

        private final String description;

        Department(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum Status {
        OPEN("Active and accepting messages"),
        RESOLVED("Customer issue resolved"),
        ESCALATED("Escalated to senior support"),
        CLOSED("Conversation closed"),
        ABANDONED("Customer stopped responding");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum Priority {
        LOW("Standard response time"),
        NORMAL("Within 24 hours"),
        HIGH("Within 4 hours"),
        URGENT("Within 1 hour");

        private final String description;

        Priority(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (sessionId == null) {
            sessionId = UUID.randomUUID();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
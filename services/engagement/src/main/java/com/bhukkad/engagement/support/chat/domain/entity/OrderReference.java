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
 * OrderReference links a chat room to a specific order for context.
 *
 * <p>This enables support agents to see order details within the chat
 * interface and helps the fraud detection engine verify that referenced
 * orders actually exist and belong to the chatting customer.</p>
 */
@Entity
@Table(name = "chat_order_references", indexes = {
    @Index(name = "idx_chat_order_room", columnList = "room_id"),
    @Index(name = "idx_chat_order_order", columnList = "order_id"),
    @Index(name = "idx_chat_order_customer", columnList = "customer_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", unique = true, nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID publicId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "order_status", length = 50)
    private String orderStatus;

    @Column(name = "order_total", precision = 10, scale = 2)
    private java.math.BigDecimal orderTotal;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "restaurant_id")
    private Long restaurantId;

    @Column(name = "restaurant_name", length = 200)
    private String restaurantName;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private LocalDateTime linkedAt;

    @PrePersist
    protected void onCreate() {
        if (publicId == null) {
            publicId = java.util.UUID.randomUUID();
        }
        if (linkedAt == null) {
            linkedAt = java.time.LocalDateTime.now();
        }
    }
}

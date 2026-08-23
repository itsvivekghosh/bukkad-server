package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Links a paid gift order to its recipient details. One row per gift order.
 * The sender pays for the order; it is delivered to the recipient identified
 * here (name / phone / address) with an optional gift message.
 */
@Entity
@Table(name = "gift_orders", indexes = {
        @Index(name = "idx_gift_orders_sender", columnList = "sender_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"order"})
@EqualsAndHashCode(exclude = {"order"})
@EntityListeners(AuditingEntityListener.class)
public class GiftOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    /** Id of the purchasing user (customer); null-safe on account deletion. */
    @Column(name = "sender_user_id")
    private Long senderUserId;

    @Column(length = 100)
    private String recipientName;

    @Column(length = 15)
    private String recipientPhone;

    @Column(name = "recipient_address_id")
    private Long recipientAddressId;

    @Column(length = 500)
    private String message;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

package com.bhukkad.order.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "gift_orders", indexes = {
        @Index(name = "idx_gift_orders_sender", columnList = "senderUserId")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class GiftOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

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

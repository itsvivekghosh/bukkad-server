package com.bhukkad.order.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "gift_cards", indexes = {
        @Index(name = "uk_gift_card_code", columnList = "code", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class GiftCard {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REDEEMED = "REDEEMED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String code;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "purchased_by")
    private Long purchasedBy;
    @Column(length = 100)
    private String recipientEmail;
    @Column(length = 100)
    private String recipientName;
    @Column(columnDefinition = "TEXT")
    private String message;
    @Column(name = "redeemed_by")
    private Long redeemedBy;
    private LocalDateTime redeemedAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}

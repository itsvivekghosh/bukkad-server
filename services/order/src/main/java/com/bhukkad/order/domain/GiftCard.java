package com.bhukkad.order.domain;

import jakarta.persistence.*;
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
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String code;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance;
    @Column(nullable = false, length = 20)
    private String status;
    private LocalDateTime expiresAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
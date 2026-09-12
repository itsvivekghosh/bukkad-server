package com.bhukkad.payment.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "restaurant_settlements", indexes = {
        @Index(name = "idx_settlement_restaurant", columnList = "restaurantId")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class RestaurantSettlement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long settlementRunId;
    @Column(nullable = false)
    private Long restaurantId;
    @Column(nullable = false)
    private Integer orderCount;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal grossAmount;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal commission;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal netAmount;
    @Column(nullable = false, length = 20)
    private String status;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
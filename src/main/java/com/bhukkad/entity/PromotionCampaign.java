package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "promotion_campaigns")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PromotionCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 30)
    private String campaignType;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Double discountPercent;

    private Double minOrderAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id")
    private Restaurant restaurant;

    private Double maxDiscountAmount;

    private Double flatDiscountAmount;

    @Column(nullable = false)
    private Boolean freeDelivery = false;

    @Column(nullable = false)
    private Integer priority = 0;

    private Integer usageLimit;

    private Integer perUserLimit = 1;

    @Column(nullable = false)
    private Boolean isActive = true;

    private LocalDateTime startsAt;

    private LocalDateTime endsAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

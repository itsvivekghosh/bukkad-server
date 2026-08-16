package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "dynamic_pricing_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class DynamicPricingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleType type;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private Integer dayOfWeek; // 1=Monday, 7=Sunday, 0=All days

    @Column(nullable = false)
    private Double discountPercent = 0.0; // e.g., 20.0 for 20% off

    @Column(nullable = false)
    private Double surgePercent = 0.0; // e.g., 15.0 for 15% surge

    @Column(nullable = false)
    private Double minOrderAmount = 0.0;

    @Column(nullable = false)
    private Double maxDiscountAmount = 0.0;

    @Column(nullable = false)
    private Integer priority = 0; // Higher priority rules apply first

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum RuleType {
        HAPPY_HOUR,     // Time-based discount
        SURGE,          // Demand-based surge
        WEEKDAY_SPECIAL, // Day-based pricing
        WEEKEND_SPECIAL  // Weekend pricing
    }
}
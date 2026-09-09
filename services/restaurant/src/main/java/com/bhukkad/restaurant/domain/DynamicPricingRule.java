package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "dynamic_pricing_rules")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class DynamicPricingRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long restaurantId;
    @Column(nullable = false, length = 100)
    private String ruleName;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal multiplier;
    private LocalTime startTime;
    private LocalTime endTime;
    @Column(nullable = false)
    private Boolean active = true;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

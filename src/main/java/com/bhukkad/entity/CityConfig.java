package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Per-city platform configuration (Multi-city/Region Support).
 *
 * <p>Each row carries the operational defaults for one city: currency, timezone,
 * supported payment methods, default minimum order amount, and serviceability
 * switch. Delivery zones still own the geographic/fee model; this config drives
 * the city-level defaults surfaced to customers and B2B storefronts.</p>
 */
@Entity
@Table(name = "city_configs", indexes = {
        @Index(name = "uk_city_configs_city", columnList = "city", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class CityConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, length = 10)
    private String currency = "INR";

    @Column(nullable = false, length = 60)
    private String timezone = "Asia/Kolkata";

    /** Comma-separated payment method names, e.g. "UPI,CARD,WALLET". */
    @Column(length = 255)
    private String supportedPaymentMethods;

    @Column(nullable = false)
    private Double defaultMinOrderAmount = 0.0;

    @Column(nullable = false)
    private Boolean isServiceable = true;

    @Column(nullable = false)
    private Boolean isActive = true;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}

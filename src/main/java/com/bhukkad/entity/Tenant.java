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
 * A white-label B2B tenant (White-label Solution).
 *
 * <p>Each tenant gets an isolated storefront identity (domain, brand name, logo,
 * theme colour, currency). Restaurants may be attached to a tenant via
 * {@code restaurants.tenant_id}; public restaurant listing honours the
 * {@code X-Tenant-Id} request header so each tenant only sees its own catalog.</p>
 */
@Entity
@Table(name = "tenants", indexes = {
        @Index(name = "uk_tenants_domain", columnList = "domain", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 120)
    private String domain;

    @Column(length = 120)
    private String brandName;

    @Column(length = 500)
    private String logoUrl;

    @Column(length = 30)
    private String themeColor;

    @Column(nullable = false, length = 10)
    private String currency = "INR";

    @Column(nullable = false)
    private Boolean isActive = true;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}

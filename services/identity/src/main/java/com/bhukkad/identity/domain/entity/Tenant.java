package com.bhukkad.identity.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

/**
 * White-label B2B tenant (port of monolith {@code entity.Tenant}). Public
 * restaurant listing honours the {@code X-Tenant-Id} header.
 */
@Entity
@Table(name = "tenants", indexes = {
        @Index(name = "uk_tenants_domain", columnList = "domain", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
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
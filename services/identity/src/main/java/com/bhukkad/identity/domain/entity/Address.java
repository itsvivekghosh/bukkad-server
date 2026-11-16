package com.bhukkad.identity.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

import com.bhukkad.common.tenant.TenantContext;

/**
 * Address entity. Multi-tenancy: rows are scoped to a tenant via
 * {@code tenant_id}; queries on this entity are filtered by the Hibernate
 * {@code tenant} filter when it is enabled on the session (bind
 * {@code :tenantId} to {@link TenantContext#getTenantId()}). New rows inherit
 * the current tenant id in {@link #prePersist()}.
 */
@Entity
@Table(name = "addresses", indexes = {
        @Index(name = "idx_address_customer", columnList = "customerId"),
        @Index(name = "idx_address_tenant", columnList = "tenant_id")
})
@FilterDef(name = "tenant", parameters = @ParamDef(name = "tenantId", type = "uuid"))
@Filter(name = "tenant", condition = "tenant_id = :tenantId")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(length = 50)
    private String label;

    @Column(nullable = false, length = 255)
    private String line1;

    @Column(length = 255)
    private String line2;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 10)
    private String zipCode;

    @Column(length = 200)
    private String landmark;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AddressType type;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    private Boolean isDefault = false;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    private void prePersist() {
        if (tenantId == null) {
            tenantId = TenantContext.getTenantId();
        }
    }

    public enum AddressType {
        HOME, WORK, OTHER
    }
}
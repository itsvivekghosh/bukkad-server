package com.bhukkad.order.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_eta_snapshots", indexes = {@Index(name = "idx_eta_order", columnList = "orderId, createdAt")})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class OrderEtaSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long orderId;
    @Column(nullable = false)
    private Integer etaMinutes;
    @Column
    private LocalDateTime etaAt;
    private Integer actualMinutes;
    private Integer confidenceLowMinutes;
    private Integer confidenceHighMinutes;
    private Double trafficFactor;
    private Double surgeMultiplier;
    @Column(length = 500)
    private String factorsSummary;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

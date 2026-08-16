package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Point-in-time ETA snapshot for delivery accuracy tracking and smarter predictions.
 */
@Entity
@Table(name = "order_eta_snapshots", indexes = {
        @Index(name = "idx_eta_snapshot_order", columnList = "order_id, recordedAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class OrderEtaSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private Integer etaMinutes;

    @Column(nullable = false)
    private LocalDateTime etaAt;

    private Integer confidenceLowMinutes;

    private Integer confidenceHighMinutes;

    private Double trafficFactor;

    private Double surgeMultiplier;

    @Column(length = 500)
    private String factorsSummary;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime recordedAt;
}

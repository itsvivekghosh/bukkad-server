package com.bhukkad.order.domain;

import jakarta.persistence.*;
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
    private Integer actualMinutes;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
package com.bhukkad.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_assignments", indexes = {
        @Index(name = "idx_assign_order", columnList = "orderId"),
        @Index(name = "idx_assign_agent", columnList = "agentId, status")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class DeliveryAssignment {
    public static final String STATUS_ASSIGNED = "ASSIGNED";
    public static final String STATUS_PICKED_UP = "PICKED_UP";
    public static final String STATUS_DELIVERED = "DELIVERED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long orderId;
    @Column(nullable = false)
    private Long agentId;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(nullable = false)
    private LocalDateTime assignedAt;
    private LocalDateTime pickedUpAt;
    private LocalDateTime deliveredAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
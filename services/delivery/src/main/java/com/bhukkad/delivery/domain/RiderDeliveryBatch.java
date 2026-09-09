package com.bhukkad.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "rider_delivery_batches", indexes = {
        @Index(name = "idx_rider_batch_agent", columnList = "agentId, status")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class RiderDeliveryBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long agentId;
    @Column(nullable = false, length = 20)
    private String status;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
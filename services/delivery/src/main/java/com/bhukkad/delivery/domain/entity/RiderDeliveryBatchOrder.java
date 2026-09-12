package com.bhukkad.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "rider_delivery_batch_orders", indexes = {
        @Index(name = "uk_batch_order", columnList = "batchId, orderId", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class RiderDeliveryBatchOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long batchId;
    @Column(nullable = false)
    private Long orderId;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
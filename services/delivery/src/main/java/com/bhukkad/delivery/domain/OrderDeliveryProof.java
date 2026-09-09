package com.bhukkad.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_delivery_proofs", indexes = {@Index(name = "idx_delivery_proof_order", columnList = "orderId")})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class OrderDeliveryProof {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long orderId;
    @Column(length = 500)
    private String photoUrl;
    @Column(length = 500)
    private String notes;
    private String signature;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
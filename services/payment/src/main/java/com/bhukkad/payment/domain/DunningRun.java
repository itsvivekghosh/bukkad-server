package com.bhukkad.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "dunning_runs", indexes = {
        @Index(name = "uk_dunning_payment_attempt", columnList = "paymentId, attempt", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class DunningRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long paymentId;
    @Column(nullable = false)
    private Integer attempt = 1;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(nullable = false)
    private LocalDateTime scheduledAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
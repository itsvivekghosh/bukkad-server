package com.bhukkad.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_zones")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class DeliveryZone {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false)
    private Boolean isActive = true;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
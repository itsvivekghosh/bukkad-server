package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "customization_options")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class CustomizationOption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long choiceId;
    @Column(nullable = false, length = 100)
    private String label;
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceDelta = BigDecimal.ZERO;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

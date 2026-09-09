package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "promo_banners", indexes = {
        @Index(name = "idx_promo_banners_active", columnList = "active")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class PromoBanner {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(length = 500)
    private String imageUrl;
    private Long campaignId;
    @Column(nullable = false)
    private Boolean active = true;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
package com.bhukkad.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "favorite_restaurants", indexes = {
        @Index(name = "idx_favorites_customer", columnList = "customerId"),
        @Index(name = "uk_favorite_customer_restaurant", columnList = "customerId, restaurantId", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class FavoriteRestaurant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private Long restaurantId;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
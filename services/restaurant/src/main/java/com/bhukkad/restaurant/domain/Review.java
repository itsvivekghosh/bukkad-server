package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "reviews", indexes = {
        @Index(name = "idx_reviews_restaurant", columnList = "restaurantId, createdAt"),
        @Index(name = "idx_reviews_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class Review {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long restaurantId;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private Integer rating;
    private Integer foodRating;
    private Integer deliveryRating;
    private String comment;
    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;
    private String ownerResponse;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @LastModifiedDate @Column(nullable = false)
    private LocalDateTime updatedAt;
}

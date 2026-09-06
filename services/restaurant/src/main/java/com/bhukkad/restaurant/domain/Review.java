package com.bhukkad.restaurant.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "reviews", indexes = {
        @Index(name = "idx_reviews_restaurant", columnList = "restaurant_id, created_at"),
        @Index(name = "idx_reviews_status", columnList = "status"),
        @Index(name = "idx_review_restaurant", columnList = "restaurant_id"),
        @Index(name = "idx_review_rating", columnList = "rating"),
        @Index(name = "idx_review_created_at", columnList = "created_at"),
        @Index(name = "idx_review_restaurant_rating", columnList = "restaurant_id, rating"),
        @Index(name = "idx_review_restaurant_created", columnList = "restaurant_id, created_at"),
        @Index(name = "idx_review_restaurant_moderation", columnList = "restaurant_id, status"),
        @Index(name = "idx_review_moderation_queue", columnList = "status, created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class Review {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long restaurantId;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private Integer rating;

    @Column(length = 1000)
    private String comment;

    private Integer foodRating;

    private Integer deliveryRating;

    @BatchSize(size = 20)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "review_images", joinColumns = @JoinColumn(name = "review_id"))
    @Column(name = "image_url", length = 500)
    private List<String> images = new ArrayList<>();

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "owner_response", columnDefinition = "TEXT")
    private String ownerResponse;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}

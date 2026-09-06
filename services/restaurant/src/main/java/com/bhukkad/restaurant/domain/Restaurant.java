package com.bhukkad.restaurant.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "restaurants", indexes = {
        @Index(name = "idx_restaurant_cuisine_active", columnList = "cuisine_id, is_active"),
        @Index(name = "idx_restaurant_name", columnList = "name"),
        @Index(name = "idx_restaurant_active", columnList = "is_active"),
        @Index(name = "idx_restaurant_open", columnList = "is_open"),
        @Index(name = "idx_restaurant_rating", columnList = "avg_rating"),
        @Index(name = "idx_restaurant_active_open", columnList = "is_active, is_open"),
        @Index(name = "idx_restaurant_active_rating", columnList = "is_active, avg_rating"),
        @Index(name = "idx_restaurant_created_at", columnList = "created_at"),
        @Index(name = "idx_restaurant_min_order", columnList = "minimum_order_amount")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false)
    private Long cuisineId;

    private String address;

    @Column(length = 20)
    private String phone;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private LocalTime openingTime = java.time.LocalTime.of(10, 0);

    @Column(nullable = false)
    private LocalTime closingTime = java.time.LocalTime.of(23, 0);

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(nullable = false)
    private Boolean isOpen = true;

    @Column(name = "avg_rating", nullable = false)
    private Double avgRating = 0.0;

    private Integer totalReviews = 0;

    private Integer averageDeliveryTime;

    private Double minimumOrderAmount;

    private Double deliveryFee;

    @Column(nullable = false)
    private Boolean freeDeliveryAvailable = false;

    private Double freeDeliveryAbove;

    private Double commissionPercent;

    @Column(nullable = false)
    private Boolean isPureVeg = false;
    @Column
    private Double latitude;
    @Column
    private Double longitude;
    @Column(name = "delivery_radius_km")
    private Integer deliveryRadiusKm = 5;

    @Column(length = 50)
    private String licenseNumber;

    @Column(length = 50)
    private String fssaiNumber;

    @Column(nullable = false)
    private Boolean busyMode = false;

    private LocalDateTime busyUntil;

    @Column(nullable = false)
    private Integer extraPrepMinutes = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OnboardingStatus onboardingStatus = OnboardingStatus.APPROVED;

    @Column(length = 255)
    private String onboardingRejectionReason;

    @Column(name = "tenant_id")
    private Long tenantId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    @CollectionTable(name = "restaurant_features", joinColumns = @JoinColumn(name = "restaurant_id"))
    @Column(name = "feature", length = 100)
    private Set<String> features = new HashSet<>();

    @Column(length = 100)
    private String virtualBrandName;

    @ElementCollection(fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    @CollectionTable(name = "restaurant_gallery", joinColumns = @JoinColumn(name = "restaurant_id"))
    @Column(name = "image_url", length = 500)
    private Set<String> galleryImages = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.BatchSize(size = 50)
    @CollectionTable(name = "restaurant_food_types", joinColumns = @JoinColumn(name = "restaurant_id"))
    @Column(name = "food_type")
    private Set<FoodType> foodTypes = new HashSet<>();

    public enum FoodType {
        VEG, NON_VEG, VEGAN, GLUTEN_FREE
    }

    public enum OnboardingStatus {
        PENDING_VERIFICATION,
        APPROVED,
        REJECTED,
        SUSPENDED
    }
}

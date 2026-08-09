package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "restaurants", indexes = {
        @Index(name = "idx_restaurant_name", columnList = "name"),
        @Index(name = "idx_restaurant_owner", columnList = "owner_id"),
        @Index(name = "idx_restaurant_active", columnList = "isActive"),
        @Index(name = "idx_restaurant_open", columnList = "isOpen"),
        @Index(name = "idx_restaurant_pure_veg", columnList = "isPureVeg"),
        @Index(name = "idx_restaurant_rating", columnList = "averageRating"),
        @Index(name = "idx_restaurant_active_open", columnList = "isActive, isOpen"),
        @Index(name = "idx_restaurant_active_rating", columnList = "isActive, averageRating"),
        @Index(name = "idx_restaurant_created_at", columnList = "createdAt"),
        @Index(name = "idx_restaurant_delivery_fee", columnList = "deliveryFee"),
        @Index(name = "idx_restaurant_min_order", columnList = "minimumOrderAmount")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private RestaurantOwner owner;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "address_id")
    private Address address;

    @ManyToMany
    @JoinTable(
            name = "restaurant_cuisines",
            joinColumns = @JoinColumn(name = "restaurant_id"),
            inverseJoinColumns = @JoinColumn(name = "cuisine_id")
    )
    private Set<Cuisine> cuisines = new HashSet<>();

    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL)
    private List<MenuCategory> menuCategories = new ArrayList<>();

    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL)
    private List<Review> reviews = new ArrayList<>();

    private String imageUrl;

    @ElementCollection
    private List<String> galleryImages = new ArrayList<>();

    @Column(nullable = false)
    private LocalTime openingTime;

    @Column(nullable = false)
    private LocalTime closingTime;

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(nullable = false)
    private Boolean isOpen = true;

    private Double averageRating = 0.0;

    private Integer totalReviews = 0;

    private Integer averageDeliveryTime; // in minutes

    private Double minimumOrderAmount;

    private Double deliveryFee;

    private Boolean freeDeliveryAvailable = false;

    private Double freeDeliveryAbove;

    @Column(nullable = false)
    private Boolean isPureVeg = false;

    @ElementCollection
    @Enumerated(EnumType.STRING)
    private Set<FoodType> foodTypes = new HashSet<>();

    @ElementCollection
    private Set<String> features = new HashSet<>(); // e.g., "Live Tracking", "Hygiene Certified"

    private String licenseNumber;

    private String fssaiNumber;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public enum FoodType {
        VEG, NON_VEG, VEGAN, GLUTEN_FREE
    }
}
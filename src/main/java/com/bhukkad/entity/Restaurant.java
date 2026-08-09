package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
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
@ToString(exclude = {"owner", "cuisines", "menuCategories", "reviews", "address"})
@EqualsAndHashCode(exclude = {"owner", "cuisines", "menuCategories", "reviews", "address"}, callSuper = false)
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private RestaurantOwner owner;

    @JsonIgnore
    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "address_id")
    private Address address;

    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "restaurant_cuisines",
            joinColumns = @JoinColumn(name = "restaurant_id"),
            inverseJoinColumns = @JoinColumn(name = "cuisine_id")
    )
    private Set<Cuisine> cuisines = new HashSet<>();

    @JsonIgnore
    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MenuCategory> menuCategories = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Review> reviews = new ArrayList<>();

    @Column(length = 500)
    private String imageUrl;

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
    private Integer averageDeliveryTime;
    private Double minimumOrderAmount;
    private Double deliveryFee;

    @Column(nullable = false)
    private Boolean freeDeliveryAvailable = false;

    private Double freeDeliveryAbove;

    @Column(nullable = false)
    private Boolean isPureVeg = false;

    @Column(length = 50)
    private String licenseNumber;

    @Column(length = 50)
    private String fssaiNumber;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "restaurant_features", joinColumns = @JoinColumn(name = "restaurant_id"))
    @Column(name = "feature", length = 100)
    private Set<String> features = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "restaurant_gallery", joinColumns = @JoinColumn(name = "restaurant_id"))
    @Column(name = "image_url", length = 500)
    private List<String> galleryImages = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "restaurant_food_types", joinColumns = @JoinColumn(name = "restaurant_id"))
    @Column(name = "food_type")
    private Set<FoodType> foodTypes = new HashSet<>();

    public enum FoodType {
        VEG, NON_VEG, VEGAN, GLUTEN_FREE
    }
}
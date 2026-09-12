package com.bhukkad.restaurant.domain.entity;

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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "menu_items", indexes = {
        @Index(name = "idx_menu_restaurant", columnList = "restaurant_id, is_available"),
        @Index(name = "idx_menu_restaurant_name", columnList = "restaurant_id, name"),
        @Index(name = "idx_menu_item_category", columnList = "category_id"),
        @Index(name = "idx_menu_item_price", columnList = "price"),
        @Index(name = "idx_menu_item_available", columnList = "is_available"),
        @Index(name = "idx_menu_item_food_type", columnList = "food_type"),
        @Index(name = "idx_menu_item_is_veg", columnList = "is_veg"),
        @Index(name = "idx_menu_item_bestseller", columnList = "bestseller"),
        @Index(name = "idx_menu_item_recommended", columnList = "recommended"),
        @Index(name = "idx_menu_item_rating", columnList = "average_rating"),
        @Index(name = "idx_menu_item_category_available", columnList = "category_id, is_available"),
        @Index(name = "idx_menu_item_created_at", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long restaurantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(precision = 5, scale = 2)
    private BigDecimal discountPercentage;

    @Column(nullable = false)
    private Boolean isAvailable = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FoodType foodType = FoodType.VEG;

    @Column(nullable = false)
    private Boolean isVeg = true;

    @Column(nullable = false)
    private Boolean isSpicy = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SpiceLevel spiceLevel;

    @Column(length = 500)
    private String imageUrl;

    private Integer preparationTime;

    @Column(nullable = false)
    private Boolean bestseller = false;

    @Column(nullable = false)
    private Boolean recommended = false;

    private Integer calories;

    @Column(length = 50)
    private String servingSize;

    @Column(nullable = false)
    private Double averageRating = 0.0;

    private Integer totalRatings = 0;

    private Integer stockQuantity;

    @CreatedDate
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 50)
    @CollectionTable(name = "menu_item_tags", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Column(name = "tag", length = 50)
    private Set<String> tags = new HashSet<>();

    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "menu_item_allergens", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Column(name = "allergen", length = 50)
    private Set<String> allergens = new HashSet<>();

    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "menu_item_ingredients", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Column(name = "ingredient", length = 100)
    private Set<String> ingredients = new HashSet<>();

    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "menu_item_images", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Column(name = "image_url", length = 500)
    private List<String> additionalImages = new ArrayList<>();

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum FoodType {
        VEG, NON_VEG, VEGAN, EGGETARIAN
    }

    public enum SpiceLevel {
        MILD, MEDIUM, HOT, EXTRA_HOT
    }
}

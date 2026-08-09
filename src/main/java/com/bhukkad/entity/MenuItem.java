package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "menu_items", indexes = {
        @Index(name = "idx_menu_item_category", columnList = "category_id"),
        @Index(name = "idx_menu_item_name", columnList = "name"),
        @Index(name = "idx_menu_item_price", columnList = "price"),
        @Index(name = "idx_menu_item_available", columnList = "available"),
        @Index(name = "idx_menu_item_food_type", columnList = "foodType"),
        @Index(name = "idx_menu_item_is_veg", columnList = "isVeg"),
        @Index(name = "idx_menu_item_bestseller", columnList = "bestseller"),
        @Index(name = "idx_menu_item_recommended", columnList = "recommended"),
        @Index(name = "idx_menu_item_rating", columnList = "averageRating"),
        @Index(name = "idx_menu_item_category_available", columnList = "category_id, available"),
        @Index(name = "idx_menu_item_category_veg", columnList = "category_id, isVeg"),
        @Index(name = "idx_menu_item_created_at", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private MenuCategory category;

    @Column(nullable = false)
    private Double price;

    private Double originalPrice;

    private Double discountPercentage;

    @Column(nullable = false)
    private Boolean available = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FoodType foodType;

    @Column(nullable = false)
    private Boolean isVeg;

    private Boolean isSpicy = false;

    @Enumerated(EnumType.STRING)
    private SpiceLevel spiceLevel;

    @ElementCollection
    private Set<String> allergens = new HashSet<>();

    private String imageUrl;

    @ElementCollection
    private List<String> additionalImages = new ArrayList<>();

    private Integer preparationTime; // in minutes

    private Boolean bestseller = false;

    private Boolean recommended = false;

    private Integer calories;

    private String servingSize;

    @ElementCollection
    private Set<String> ingredients = new HashSet<>();

    private Double averageRating = 0.0;

    private Integer totalRatings = 0;

    @OneToMany(mappedBy = "menuItem", cascade = CascadeType.ALL)
    private List<CustomizationOption> customizationOptions = new ArrayList<>();

    @ElementCollection
    private Set<String> tags = new HashSet<>(); // e.g., "Chef's Special", "New"

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public enum FoodType {
        VEG, NON_VEG, VEGAN, EGGETARIAN
    }

    public enum SpiceLevel {
        MILD, MEDIUM, HOT, EXTRA_HOT
    }
}
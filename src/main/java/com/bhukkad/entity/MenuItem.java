package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
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
@ToString(exclude = {"category", "customizationOptions"})
@EqualsAndHashCode(exclude = {"category", "customizationOptions"}, callSuper = false)
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @JsonIgnore
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
    @Column(nullable = false, length = 20)
    private FoodType foodType;

    @Column(nullable = false)
    private Boolean isVeg;

    private Boolean isSpicy = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SpiceLevel spiceLevel;

    @Column(length = 500)
    private String imageUrl;

    private Integer preparationTime;
    private Boolean bestseller = false;
    private Boolean recommended = false;
    private Integer calories;

    @Column(length = 50)
    private String servingSize;

    private Double averageRating = 0.0;
    private Integer totalRatings = 0;

    @JsonIgnore
    @OneToMany(mappedBy = "menuItem", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<CustomizationOption> customizationOptions = new ArrayList<>();

    // ALL ElementCollections must be EAGER to avoid LazyInitializationException
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "menu_item_tags", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Column(name = "tag", length = 50)
    private Set<String> tags = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "menu_item_allergens", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Column(name = "allergen", length = 50)
    private Set<String> allergens = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "menu_item_ingredients", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Column(name = "ingredient", length = 100)
    private Set<String> ingredients = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "menu_item_images", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Column(name = "image_url", length = 500)
    private List<String> additionalImages = new ArrayList<>();

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
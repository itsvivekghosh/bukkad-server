package com.bhukkad.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Menu grouping inside a restaurant (Batch 4 wave 2 migration).
 *
 * <p>Restaurant-service port of the monolith
 * {@code com.bhukkad.entity.MenuCategory}. The service-local variant stores a
 * plain {@code restaurantId} instead of a JPA relation, consistent with
 * {@link MenuItem}. The monolith keeps a working copy (with the full
 * {@code @ManyToOne}/{@code @OneToMany} graph) until the gateway flips.
 */
@Entity
@Table(name = "menu_categories", indexes = {
        @Index(name = "idx_category_restaurant", columnList = "restaurant_id"),
        @Index(name = "idx_category_active", columnList = "active"),
        @Index(name = "idx_category_restaurant_active", columnList = "restaurant_id, active"),
        @Index(name = "idx_category_restaurant_order", columnList = "restaurant_id, display_order")
})
@Getter
@Setter
public class MenuCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @Column(nullable = false)
    private Boolean active = true;
}

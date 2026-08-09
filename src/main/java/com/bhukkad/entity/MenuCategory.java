package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "menu_categories", indexes = {
        @Index(name = "idx_category_restaurant", columnList = "restaurant_id"),
        @Index(name = "idx_category_active", columnList = "active"),
        @Index(name = "idx_category_display_order", columnList = "displayOrder"),
        @Index(name = "idx_category_restaurant_active", columnList = "restaurant_id, active"),
        @Index(name = "idx_category_restaurant_order", columnList = "restaurant_id, displayOrder")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MenuCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL)
    private List<MenuItem> menuItems = new ArrayList<>();

    private Integer displayOrder = 0;

    @Column(nullable = false)
    private Boolean active = true;
}
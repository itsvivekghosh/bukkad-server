package com.bhukkad.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Materialized trending-dish summary owned by the ANALYTICS domain.
 * Fed by the ORDER_ITEMS_SNAPSHOT outbox event (not by cross-domain SQL joins).
 */
@Entity
@Table(name = "trending_dishes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendingDish {

    @Id
    @Column(name = "menu_item_id")
    private Long menuItemId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "dish_name", nullable = false)
    private String dishName;

    @Column(name = "quantity_sold", nullable = false)
    private Long quantitySold;

    @Column(name = "last_order_at", nullable = false)
    private LocalDateTime lastOrderAt;
}

package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_item_customizations", indexes = {
        @Index(name = "idx_order_item_custom_order_item", columnList = "order_item_id"),
        @Index(name = "idx_order_item_custom_choice", columnList = "customization_choice_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemCustomization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customization_choice_id", nullable = false)
    private CustomizationChoice customizationChoice;

    private Double additionalPrice;
}
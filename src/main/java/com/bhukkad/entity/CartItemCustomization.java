package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cart_item_customizations", indexes = {
        @Index(name = "idx_cart_item_custom_cart_item", columnList = "cart_item_id"),
        @Index(name = "idx_cart_item_custom_choice", columnList = "customization_choice_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartItemCustomization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_item_id", nullable = false)
    private CartItem cartItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customization_choice_id", nullable = false)
    private CustomizationChoice customizationChoice;
}
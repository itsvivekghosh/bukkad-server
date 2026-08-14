package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartResponse {
    private Long id;
    /** @deprecated Use {@link #restaurantCarts} for multi-restaurant carts. */
    @Deprecated
    private Long restaurantId;
    /** @deprecated Use {@link #restaurantCarts} for multi-restaurant carts. */
    @Deprecated
    private String restaurantName;
    /** @deprecated Use {@link #restaurantCarts} for multi-restaurant carts. */
    @Deprecated
    private List<CartItemResponse> items;
    private List<RestaurantCartGroup> restaurantCarts;
    private Double subtotal;
    private Integer itemCount;
}
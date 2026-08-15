package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UnifiedSearchResponse {
    private List<RestaurantResponse> restaurants;
    private List<MenuItemResponse> menuItems;
    private int restaurantCount;
    private int menuItemCount;
}

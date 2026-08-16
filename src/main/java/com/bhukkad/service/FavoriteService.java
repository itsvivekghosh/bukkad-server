package com.bhukkad.service;

import com.bhukkad.dto.response.FavoriteRestaurantResponse;

import java.util.List;

public interface FavoriteService {
    List<FavoriteRestaurantResponse> listFavorites();
    FavoriteRestaurantResponse addFavorite(Long restaurantId);
    void removeFavorite(Long restaurantId);
}

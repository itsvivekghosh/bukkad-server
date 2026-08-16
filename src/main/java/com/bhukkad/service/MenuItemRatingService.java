package com.bhukkad.service;

import com.bhukkad.dto.request.MenuItemRatingRequest;
import com.bhukkad.dto.response.MenuItemRatingResponse;

import java.util.List;

public interface MenuItemRatingService {
    MenuItemRatingResponse rateMenuItem(MenuItemRatingRequest request);
    List<MenuItemRatingResponse> getMenuItemRatings(Long menuItemId);
}

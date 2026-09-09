package com.bhukkad.personalization.service;

import com.bhukkad.personalization.dto.FeedRankResponse;
import com.bhukkad.personalization.dto.RecommendationResponse;

import java.util.List;

public interface RecommendationService {

    List<RecommendationResponse> reorderSuggestions(Long customerId);

    List<RecommendationResponse> collaborativeSuggestions(Long customerId);

    List<RecommendationResponse> timeAwareSuggestions(Long customerId);

    FeedRankResponse rankRestaurantsForCustomer(Long customerId, List<Long> restaurantIds);
}

package com.bhukkad.personalization.domain.service;

import com.bhukkad.personalization.api.dto.response.FeedRankResponse;
import com.bhukkad.personalization.api.dto.response.RecommendationResponse;

import java.util.List;

public interface RecommendationService {

    List<RecommendationResponse> reorderSuggestions(Long customerId);

    List<RecommendationResponse> collaborativeSuggestions(Long customerId);

    List<RecommendationResponse> timeAwareSuggestions(Long customerId);

    FeedRankResponse rankRestaurantsForCustomer(Long customerId, List<Long> restaurantIds);
}

package com.bhukkad.personalization.service;

import java.util.List;

public interface RecommendationQueryService {

    List<Object[]> findCustomerItemFrequencies(Long customerId, int limit);

    List<Object[]> findCoOrderedItems(List<Long> itemIds, Long customerId, int limit);

    List<Object[]> findCustomerRestaurantAffinities(Long customerId, int limit);
}

package com.bhukkad.survey.domain.service;

import com.bhukkad.survey.api.dto.response.TrendingDishResponse;

import java.util.List;

/**
 * Trending dishes for the home feed.
 *
 * <p>Ranks menu items by quantity sold from the ANALYTICS-owned
 * {@code trending_dishes} summary table (fed by the ORDER_ITEMS_SNAPSHOT outbox
 * event). Results are cached in-process for 60 seconds so the anonymous home
 * feed never hits the database more than once per minute.
 */
public interface TrendingDishService {

    /**
     * Returns the top {@code limit} trending dishes, cached for 60 seconds.
     *
     * @param limit desired result size; non-positive values fall back to 10,
     *              values above 50 are capped
     * @return ranked dishes, never {@code null}; empty when the query fails
     */
    List<TrendingDishResponse> trending(int limit);
}
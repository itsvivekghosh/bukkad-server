package com.bhukkad.survey.domain.service;

import com.bhukkad.survey.api.dto.response.SurveyRatingsResponse;
import com.bhukkad.survey.domain.entity.DeliverySurvey;

/**
 * Post-delivery satisfaction surveys.
 *
 * <p>A customer may submit exactly one survey per delivered order; the service
 * verifies ownership and delivery status before persisting, and aggregates the
 * submitted ratings per restaurant for analytics.
 */
public interface SurveyService {

    /**
     * Submits a post-delivery survey for the customer's delivered order.
     *
     * @param customerId     authenticated customer id
     * @param orderId        delivered order being surveyed
     * @param restaurantId   restaurant the order belongs to (denormalized for aggregation)
     * @param ratingDelivery delivery rating (1-5), optional
     * @param ratingFood     food rating (1-5), optional
     * @param ratingSpeed    speed rating (1-5), optional
     * @param comment        optional free-text feedback (max 1000 chars)
     * @return the persisted survey
     */
    DeliverySurvey submitSurvey(Long customerId, Long orderId, Long restaurantId,
                                Integer ratingDelivery, Integer ratingFood,
                                Integer ratingSpeed, String comment);

    /**
     * Returns the average survey ratings for a restaurant, backed by the read
     * replica. Missing data (no surveys, or a question no survey answered)
     * surfaces as {@code 0.0} so consumers never see nulls.
     *
     * @param restaurantId restaurant scope
     * @return aggregated ratings and survey count, never {@code null}
     */
    SurveyRatingsResponse getRestaurantRatings(Long restaurantId);
}
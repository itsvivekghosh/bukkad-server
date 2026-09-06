package com.bhukkad.survey.dto.response;

/**
 * Aggregated post-delivery survey ratings for a restaurant.
 *
 * @param restaurantId    restaurant these ratings belong to
 * @param ratingDelivery  average delivery rating (1-5), {@code 0.0} when no data
 * @param ratingFood      average food rating (1-5), {@code 0.0} when no data
 * @param ratingSpeed     average delivery speed rating (1-5), {@code 0.0} when no data
 * @param surveyCount     number of submitted surveys included in the averages
 */
public record SurveyRatingsResponse(
        Long restaurantId,
        Double ratingDelivery,
        Double ratingFood,
        Double ratingSpeed,
        Long surveyCount
) {}
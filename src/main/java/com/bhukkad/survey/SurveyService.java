package com.bhukkad.survey;

import com.bhukkad.common.datasource.UseReadReplica;
import com.bhukkad.dto.response.SurveyRatingsResponse;
import com.bhukkad.entity.DeliverySurvey;
import com.bhukkad.entity.Order;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.repository.DeliverySurveyRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Post-delivery satisfaction surveys.
 *
 * <p>A customer may submit exactly one survey per delivered order; the service
 * verifies ownership and delivery status before persisting, and aggregates the
 * submitted ratings per restaurant for analytics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyService {

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;
    private static final int MAX_COMMENT_LENGTH = 1000;

    private final OrderRepository orderRepository;
    private final DeliverySurveyRepository surveyRepository;

    /**
     * Submits a post-delivery survey for the customer's delivered order.
     *
     * @param customerId     authenticated customer id
     * @param orderId        delivered order being surveyed
     * @param ratingDelivery delivery rating (1-5), optional
     * @param ratingFood     food rating (1-5), optional
     * @param ratingSpeed    speed rating (1-5), optional
     * @param comment        optional free-text feedback (max 1000 chars)
     * @return the persisted survey
     * @throws ResourceNotFoundException when the order does not exist
     * @throws UnauthorizedException     when the order belongs to another customer
     * @throws BusinessException         when the order is not delivered, the survey
     *                                   was already submitted, or a rating/comment
     *                                   is out of range
     */
    @Transactional
    public DeliverySurvey submitSurvey(Long customerId, Long orderId,
                                       Integer ratingDelivery, Integer ratingFood,
                                       Integer ratingSpeed, String comment) {
        if (customerId == null || orderId == null) {
            throw new BusinessException("Customer id and order id are required");
        }
        validateRatings(ratingDelivery, ratingFood, ratingSpeed);
        validateComment(comment);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getCustomer() == null || !order.getCustomer().getId().equals(customerId)) {
            throw new UnauthorizedException("You can only survey your own orders");
        }
        if (order.getStatus() != Order.OrderStatus.DELIVERED) {
            throw new BusinessException("Surveys can only be submitted for delivered orders");
        }
        if (surveyRepository.findByOrderId(orderId).isPresent()) {
            throw new BusinessException("Survey already submitted for this order");
        }

        DeliverySurvey survey = new DeliverySurvey();
        survey.setOrder(order);
        survey.setCustomer(order.getCustomer());
        survey.setRatingDelivery(ratingDelivery);
        survey.setRatingFood(ratingFood);
        survey.setRatingSpeed(ratingSpeed);
        survey.setComment(comment != null ? comment.trim() : null);
        survey.setSubmittedAt(LocalDateTime.now());

        DeliverySurvey saved = surveyRepository.save(survey);
        Long restaurantId = order.getRestaurant() != null ? order.getRestaurant().getId() : null;
        log.info("Survey submitted | orderId={} | customerId={} | restaurantId={}",
                orderId, customerId, restaurantId);
        return saved;
    }

    /**
     * Returns the average survey ratings for a restaurant, backed by the read
     * replica. Missing data (no surveys, or a question no survey answered)
     * surfaces as {@code 0.0} so consumers never see nulls.
     *
     * @param restaurantId restaurant scope
     * @return aggregated ratings and survey count, never {@code null}
     */
    @UseReadReplica
    @Transactional(readOnly = true)
    public SurveyRatingsResponse getRestaurantRatings(Long restaurantId) {
        if (restaurantId == null) {
            throw new BusinessException("Restaurant id is required");
        }
        List<Object[]> rows = surveyRepository.findRestaurantAverages(restaurantId);
        if (rows.isEmpty()) {
            return new SurveyRatingsResponse(restaurantId, 0.0, 0.0, 0.0, 0L);
        }
        // Hibernate 6 may wrap the single aggregate row in a nested array
        // ([ [avgDelivery, avgFood, avgSpeed, count] ]); unwrap defensively.
        Object[] row = rows.get(0);
        if (row.length == 1 && row[0] instanceof Object[] nested) {
            row = nested;
        }
        Double delivery = nullableAverage(row[0]);
        Double food = nullableAverage(row[1]);
        Double speed = nullableAverage(row[2]);
        Long count = row.length > 3 && row[3] != null ? ((Number) row[3]).longValue() : 0L;
        return new SurveyRatingsResponse(restaurantId, delivery, food, speed, count);
    }

    private void validateRatings(Integer ratingDelivery, Integer ratingFood, Integer ratingSpeed) {
        // Ratings are optional (any may be null); List.of would NPE on nulls.
        for (Integer rating : Arrays.asList(ratingDelivery, ratingFood, ratingSpeed)) {
            if (rating != null && (rating < MIN_RATING || rating > MAX_RATING)) {
                throw new BusinessException("Ratings must be between 1 and 5");
            }
        }
    }

    private void validateComment(String comment) {
        if (comment != null && comment.trim().length() > MAX_COMMENT_LENGTH) {
            throw new BusinessException("Comment must be at most " + MAX_COMMENT_LENGTH + " characters");
        }
    }

    private Double nullableAverage(Object value) {
        return value != null
                ? PriceCalculator.roundToTwoDecimals(((Number) value).doubleValue())
                : 0.0;
    }
}

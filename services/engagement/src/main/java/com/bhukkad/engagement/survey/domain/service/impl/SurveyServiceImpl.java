package com.bhukkad.engagement.survey.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.engagement.survey.domain.service.SurveyService;
import com.bhukkad.engagement.survey.api.dto.response.SurveyRatingsResponse;
import com.bhukkad.engagement.survey.domain.entity.DeliverySurvey;
import com.bhukkad.engagement.survey.domain.repository.DeliverySurveyRepository;
import com.bhukkad.engagement.survey.infrastructure.client.OrderOwnershipClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Post-delivery satisfaction surveys.
 * 
 * <p>Optimizations:
 * <ul>
 *   <li>Caffeine cache for restaurant ratings aggregation (5min TTL)</li>
 *   <li>Batch validation with early exit</li>
 *   <li>Cache invalidation on survey submission</li>
 * </ul>
 */
@Service
public class SurveyServiceImpl implements SurveyService {

    private static final Logger log = LoggerFactory.getLogger(SurveyServiceImpl.class);

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;
    private static final int MAX_COMMENT_LENGTH = 1000;

    private final DeliverySurveyRepository surveyRepository;
    private final OrderOwnershipClient orderOwnershipClient;

    // Caffeine cache for restaurant ratings - 5min TTL, bounded to 10000 entries
    private final Cache<Long, SurveyRatingsResponse> restaurantRatingsCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();

    public SurveyServiceImpl(DeliverySurveyRepository surveyRepository,
                             OrderOwnershipClient orderOwnershipClient) {
        this.orderOwnershipClient = orderOwnershipClient;
        this.surveyRepository = surveyRepository;
    }

    /**
     * Submits a post-delivery survey for the customer's delivered order.
     *
     * @throws BusinessException     when a rating/comment is out of range
     * @throws UnauthorizedException when {@code customerId} is missing
     * @throws DuplicateRequestException when a survey was already submitted for the order
     */
    @Override
    @Transactional
    public DeliverySurvey submitSurvey(Long customerId, Long orderId, Long restaurantId,
                                       Integer ratingDelivery, Integer ratingFood,
                                       Integer ratingSpeed, String comment) {
        if (customerId == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        if (orderId == null) {
            throw new BusinessException("Order id is required");
        }
        // SV-1: ratings must come from the order's owner. Ownership is the
        // order service's fact (mesh lookup); any failure denies — survey data
        // poisoning was possible with a bare orderId before this check.
        if (!orderOwnershipClient.ownsOrder(customerId, orderId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Not the owner of order " + orderId);
        }
        validateRatings(ratingDelivery, ratingFood, ratingSpeed);
        validateComment(comment);
        if (surveyRepository.findByOrderId(orderId).isPresent()) {
            throw new DuplicateRequestException("Survey already submitted for this order");
        }

        DeliverySurvey survey = new DeliverySurvey();
        survey.setOrderId(orderId);
        survey.setCustomerId(customerId);
        survey.setRestaurantId(restaurantId);
        survey.setRatingDelivery(ratingDelivery);
        survey.setRatingFood(ratingFood);
        survey.setRatingSpeed(ratingSpeed);
        survey.setComment(comment != null ? comment.trim() : null);
        survey.setSubmittedAt(LocalDateTime.now());

        DeliverySurvey saved = surveyRepository.save(survey);
        
        // Invalidate restaurant ratings cache after new survey submission
        if (restaurantId != null) {
            restaurantRatingsCache.invalidate(restaurantId);
            log.debug("SURVEY_RATINGS_CACHE_INVALIDATED | restaurantId={}", restaurantId);
        }
        
        log.info("Survey submitted | orderId={} | customerId={} | restaurantId={}",
                orderId, customerId, restaurantId);
        return saved;
    }

    /**
     * Returns the average survey ratings for a restaurant. Missing data (no
     * surveys, or a question no survey answered) surfaces as {@code 0.0} so
     * consumers never see nulls.
     * 
     * <p>Results are cached for 5 minutes to reduce database load for
     * frequently-accessed restaurants.
     */
    @Override
    @Transactional(readOnly = true)
    public SurveyRatingsResponse getRestaurantRatings(Long restaurantId) {
        if (restaurantId == null) {
            throw new BusinessException("Restaurant id is required");
        }

        // Check cache first
        SurveyRatingsResponse cached = restaurantRatingsCache.getIfPresent(restaurantId);
        if (cached != null) {
            return cached;
        }

        List<Object[]> rows = surveyRepository.findRestaurantAverages(restaurantId);
        if (rows.isEmpty()) {
            SurveyRatingsResponse response = new SurveyRatingsResponse(restaurantId, 0.0, 0.0, 0.0, 0L);
            restaurantRatingsCache.put(restaurantId, response);
            return response;
        }
        // Hibernate 6 may wrap the single aggregate row in a nested array
        // ([ [avgDelivery, avgFood, avgSpeed, count] ]); unwrap defensively.
        Object[] row = rows.get(0);
        if (row.length == 1 && row[0] instanceof Object[] nested) {
            row = nested;
        }
        Double delivery = nullableAverage(row.length > 0 ? row[0] : null);
        Double food = nullableAverage(row.length > 1 ? row[1] : null);
        Double speed = nullableAverage(row.length > 2 ? row[2] : null);
        Long count = row.length > 3 && row[3] != null ? ((Number) row[3]).longValue() : 0L;
        
        SurveyRatingsResponse response = new SurveyRatingsResponse(restaurantId, delivery, food, speed, count);
        
        // Cache the result
        restaurantRatingsCache.put(restaurantId, response);
        
        return response;
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
                ? Math.round(((Number) value).doubleValue() * 100.0) / 100.0
                : 0.0;
    }
}

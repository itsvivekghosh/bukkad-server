package com.bhukkad.survey.domain.repository;

import com.bhukkad.survey.domain.entity.DeliverySurvey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the DeliverySurvey entity.
 */
@Repository
public interface DeliverySurveyRepository extends JpaRepository<DeliverySurvey, Long> {

    Optional<DeliverySurvey> findByOrderId(Long orderId);

    /**
     * Average delivery/food/speed ratings plus the survey count for a restaurant,
     * aggregated over the denormalized {@code restaurantId} column.
     *
     * <p>Returns a single row {@code [avgRatingDelivery, avgRatingFood, avgRatingSpeed, count]}.
     * Any average can be {@code null} when no survey for that restaurant answered
     * the corresponding question; the caller treats it as "no data". When the
     * restaurant has no surveys at all the list is empty.
     */
    @Query("SELECT AVG(s.ratingDelivery), AVG(s.ratingFood), AVG(s.ratingSpeed), COUNT(s) " +
            "FROM DeliverySurvey s WHERE s.restaurantId = :restaurantId")
    List<Object[]> findRestaurantAverages(@Param("restaurantId") Long restaurantId);
}
package com.bhukkad.repository;

import com.bhukkad.entity.DeliverySurvey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeliverySurveyRepository extends JpaRepository<DeliverySurvey, Long> {

    Optional<DeliverySurvey> findByOrderId(Long orderId);

    List<DeliverySurvey> findByCustomerId(Long customerId);

    long countByOrderId(Long orderId);

    /**
     * Average delivery/food/speed ratings plus the survey count for a restaurant,
     * aggregated over the restaurant's orders.
     *
     * <p>Returns a single row {@code [avgRatingDelivery, avgRatingFood, avgRatingSpeed, count]}.
     * Any of the average columns can be {@code null} when no survey for that
     * restaurant answered the corresponding question; the caller treats them as
     * "no data". When the restaurant has no surveys at all the list is empty.
     *
     * @param restaurantId restaurant scope
     * @return aggregate row if the restaurant has at least one survey, empty otherwise
     */
    @Query("SELECT AVG(s.ratingDelivery), AVG(s.ratingFood), AVG(s.ratingSpeed), COUNT(s) " +
            "FROM DeliverySurvey s WHERE s.order.restaurant.id = :restaurantId")
    List<Object[]> findRestaurantAverages(@Param("restaurantId") Long restaurantId);
}

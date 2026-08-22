package com.bhukkad.service;

import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Refreshes materialized-view-style summary tables that power restaurant
 * ratings and order-statistics dashboards.
 *
 * <p>These tables are not database materialized views because MySQL lacks native
 * support; instead they are ordinary tables refreshed on a short schedule by
 * this service. Reads hit the summary tables directly through lightweight DAOs
 * or native queries, keeping dashboard latency low.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterializedViewRefreshService {

    private final RestaurantRepository restaurantRepository;
    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final EntityManager entityManager;

    /**
     * Refresh all summary tables. Runs every 5 minutes.
     */
    @Scheduled(fixedRateString = "${app.db.materialized-view-refresh-ms:300000}")
    @Transactional
    public void refreshAll() {
        refreshRestaurantRatings();
        refreshRestaurantOrderStats();
    }

    /**
     * Recomputes {@code restaurant_ratings_summary} from the live {@code reviews}
     * table. This is cheaper than COUNT/AVG over the full reviews table on every
     * public read.
     */
    @Transactional
    public void refreshRestaurantRatings() {
        log.debug("Refreshing restaurant ratings summary");
        restaurantRepository.findAll().forEach(restaurant -> {
            Double avg = reviewRepository.getAverageRatingByRestaurant(restaurant.getId());
            Long total = reviewRepository.countByRestaurant(restaurant.getId());
            Long positive = reviewRepository.countByRestaurantAndStatus(
                    restaurant.getId(), com.bhukkad.entity.Review.ModerationStatus.APPROVED);

            entityManager.createNativeQuery(
                    "INSERT INTO restaurant_ratings_summary (restaurant_id, average_rating, total_reviews, positive_reviews, last_calculated_at) " +
                    "VALUES (?, ?, ?, ?, NOW()) " +
                    "ON DUPLICATE KEY UPDATE average_rating = VALUES(average_rating), total_reviews = VALUES(total_reviews), " +
                    "positive_reviews = VALUES(positive_reviews), last_calculated_at = NOW()")
                    .setParameter(1, restaurant.getId())
                    .setParameter(2, avg != null ? avg : 0.0)
                    .setParameter(3, total != null ? total.longValue() : 0L)
                    .setParameter(4, positive != null ? positive.longValue() : 0L)
                    .executeUpdate();
        });
        log.info("Refreshed restaurant ratings summary for {} restaurants", restaurantRepository.count());
    }

    /**
     * Recomputes {@code restaurant_order_stats} from the live {@code orders}
     * table so dashboard aggregates do not scan the full orders table.
     */
    @Transactional
    public void refreshRestaurantOrderStats() {
        log.debug("Refreshing restaurant order stats summary");
        restaurantRepository.findAll().forEach(restaurant -> {
            Long total = orderRepository.countByRestaurantId(restaurant.getId());
            Long delivered = orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                    restaurant.getId(),
                    com.bhukkad.entity.Order.OrderStatus.DELIVERED,
                    LocalDateTime.now().minusMonths(12));
            Long cancelled = orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                    restaurant.getId(),
                    com.bhukkad.entity.Order.OrderStatus.CANCELLED,
                    LocalDateTime.now().minusMonths(12));
            Double revenue = orderRepository.sumRestaurantRevenueSince(
                    restaurant.getId(),
                    LocalDateTime.now().minusMonths(12));

            double totalD = total != null ? total : 0;
            double revenueD = revenue != null ? revenue : 0.0;
            double avg = totalD > 0 ? revenueD / totalD : 0.0;

            entityManager.createNativeQuery(
                    "INSERT INTO restaurant_order_stats (restaurant_id, total_orders, delivered_orders, cancelled_orders, total_revenue, avg_order_value, last_calculated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, NOW()) " +
                    "ON DUPLICATE KEY UPDATE total_orders = VALUES(total_orders), delivered_orders = VALUES(delivered_orders), " +
                    "cancelled_orders = VALUES(cancelled_orders), total_revenue = VALUES(total_revenue), avg_order_value = VALUES(avg_order_value), last_calculated_at = NOW()")
                    .setParameter(1, restaurant.getId())
                    .setParameter(2, (long) totalD)
                    .setParameter(3, delivered != null ? delivered.longValue() : 0L)
                    .setParameter(4, cancelled != null ? cancelled.longValue() : 0L)
                    .setParameter(5, revenueD)
                    .setParameter(6, avg)
                    .executeUpdate();
        });
        log.info("Refreshed restaurant order stats summary for {} restaurants", restaurantRepository.count());
    }
}

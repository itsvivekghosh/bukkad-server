package com.bhukkad.service;

import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final PlatformTransactionManager transactionManager;

    private static final int BATCH_SIZE = 100;

    /**
     * Refresh all summary tables. Runs every 5 minutes.
     * Single-pod locked to avoid N×replica DB storms.
     */
    @Scheduled(fixedRateString = "${app.db.materialized-view-refresh-ms:300000}")
    @SchedulerLock(name = "materialized-view-refresh", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void refreshAll() {
        refreshRestaurantRatings();
        refreshRestaurantOrderStats();
    }

    /**
     * Recomputes {@code restaurant_ratings_summary} from the live {@code reviews}
     * table. This is cheaper than COUNT/AVG over the full reviews table on every
     * public read.
     */
    public void refreshRestaurantRatings() {
        log.debug("Refreshing restaurant ratings summary");
        int page = 0;
        long totalProcessed = 0;
        Page<com.bhukkad.entity.Restaurant> batch;
        do {
            batch = restaurantRepository.findAll(PageRequest.of(page, BATCH_SIZE, Sort.by("id")));
            for (com.bhukkad.entity.Restaurant restaurant : batch.getContent()) {
                refreshSingleRestaurantRating(restaurant.getId());
            }
            totalProcessed += batch.getNumberOfElements();
            page++;
            // Short pause to avoid tight loop starving DB at 10k restaurants
            if (batch.hasNext()) {
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        } while (batch.hasNext());
        log.info("Refreshed restaurant ratings summary for {} restaurants", totalProcessed);
    }

    private void refreshSingleRestaurantRating(Long restaurantId) {
        // Programmatic transaction: this is an internal call, so @Transactional's
        // proxy would never apply (self-invocation bypass) and the modifying
        // query would throw TransactionRequiredException. One short Tx per
        // restaurant keeps each upsert atomic without holding a transaction
        // across the whole paginated sweep.
        newTransaction().executeWithoutResult(status -> {
            Double avg = reviewRepository.getAverageRatingByRestaurant(restaurantId);
            Long total = reviewRepository.countByRestaurant(restaurantId);
            Long positive = reviewRepository.countByRestaurantAndStatus(
                    restaurantId, com.bhukkad.entity.Review.ModerationStatus.APPROVED);

            entityManager.createNativeQuery(
                    "INSERT INTO restaurant_ratings_summary (restaurant_id, average_rating, total_reviews, positive_reviews, last_calculated_at) " +
                    "VALUES (?, ?, ?, ?, NOW()) " +
                    "ON CONFLICT (restaurant_id) DO UPDATE SET average_rating = EXCLUDED.average_rating, total_reviews = EXCLUDED.total_reviews, " +
                    "positive_reviews = EXCLUDED.positive_reviews, last_calculated_at = NOW()")
                    .setParameter(1, restaurantId)
                    .setParameter(2, avg != null ? avg : 0.0)
                    .setParameter(3, total != null ? total.longValue() : 0L)
                    .setParameter(4, positive != null ? positive.longValue() : 0L)
                    .executeUpdate();
        });
    }

    /**
     * Recomputes {@code restaurant_order_stats} from the live {@code orders}
     * table so dashboard aggregates do not scan the full orders table.
     */
    public void refreshRestaurantOrderStats() {
        log.debug("Refreshing restaurant order stats summary");
        int page = 0;
        long totalProcessed = 0;
        Page<com.bhukkad.entity.Restaurant> batch;
        do {
            batch = restaurantRepository.findAll(PageRequest.of(page, BATCH_SIZE, Sort.by("id")));
            for (com.bhukkad.entity.Restaurant restaurant : batch.getContent()) {
                refreshSingleRestaurantOrderStats(restaurant.getId());
            }
            totalProcessed += batch.getNumberOfElements();
            page++;
            if (batch.hasNext()) {
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        } while (batch.hasNext());
        log.info("Refreshed restaurant order stats summary for {} restaurants", totalProcessed);
    }

    private void refreshSingleRestaurantOrderStats(Long restaurantId) {
        newTransaction().executeWithoutResult(status -> {
            Long total = orderRepository.countByRestaurantId(restaurantId);
            Long delivered = orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                    restaurantId,
                    com.bhukkad.entity.Order.OrderStatus.DELIVERED,
                    LocalDateTime.now().minusMonths(12));
            Long cancelled = orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                    restaurantId,
                    com.bhukkad.entity.Order.OrderStatus.CANCELLED,
                    LocalDateTime.now().minusMonths(12));
            Double revenue = orderRepository.sumRestaurantRevenueSince(
                    restaurantId,
                    LocalDateTime.now().minusMonths(12));

            double totalD = total != null ? total : 0;
            double revenueD = revenue != null ? revenue : 0.0;
            double avg = totalD > 0 ? revenueD / totalD : 0.0;

            entityManager.createNativeQuery(
                    "INSERT INTO restaurant_order_stats (restaurant_id, total_orders, delivered_orders, cancelled_orders, total_revenue, avg_order_value, last_calculated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, NOW()) " +
                    "ON CONFLICT (restaurant_id) DO UPDATE SET total_orders = EXCLUDED.total_orders, delivered_orders = EXCLUDED.delivered_orders, " +
                    "cancelled_orders = EXCLUDED.cancelled_orders, total_revenue = EXCLUDED.total_revenue, avg_order_value = EXCLUDED.avg_order_value, last_calculated_at = NOW()")
                    .setParameter(1, restaurantId)
                    .setParameter(2, (long) totalD)
                    .setParameter(3, delivered != null ? delivered.longValue() : 0L)
                    .setParameter(4, cancelled != null ? cancelled.longValue() : 0L)
                    .setParameter(5, revenueD)
                    .setParameter(6, avg)
                    .executeUpdate();
        });
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }
}

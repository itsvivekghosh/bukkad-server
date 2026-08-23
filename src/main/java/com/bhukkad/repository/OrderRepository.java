package com.bhukkad.repository;

import com.bhukkad.dto.response.OrderSummaryResponse;
import com.bhukkad.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    String SUMMARY_SELECT = "SELECT new com.bhukkad.dto.response.OrderSummaryResponse(" +
            "o.id, o.orderNumber, c.id, c.fullName, r.id, r.name, o.status, o.totalAmount, " +
            "o.specialInstructions, o.createdAt, o.estimatedDeliveryAt) " +
            "FROM Order o JOIN o.customer c JOIN o.restaurant r ";

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant r " +
            "JOIN FETCH r.owner " +
            "JOIN FETCH o.deliveryAddress " +
            "LEFT JOIN FETCH o.deliveryAgent " +
            "LEFT JOIN FETCH o.orderItems oi " +
            "LEFT JOIN FETCH oi.menuItem " +
            "LEFT JOIN FETCH o.payment " +
            "WHERE o.id = :id")
    Optional<Order> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant r " +
            "JOIN FETCH r.owner " +
            "JOIN FETCH o.deliveryAddress " +
            "LEFT JOIN FETCH o.deliveryAgent " +
            "LEFT JOIN FETCH o.orderItems " +
            "WHERE o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberWithDetails(@Param("orderNumber") String orderNumber);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.deliveryAddress.id = :addressId")
    long countByDeliveryAddressId(@Param("addressId") Long addressId);

    @Query(value = "SELECT COUNT(*) FROM orders WHERE delivery_address_id = :addressId", nativeQuery = true)
    long existsByDeliveryAddressId(@Param("addressId") Long addressId);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant r " +
            "JOIN FETCH r.owner " +
            "JOIN FETCH o.deliveryAddress " +
            "LEFT JOIN FETCH o.deliveryAgent " +
            "WHERE o.customer.id = :customerId " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByCustomerIdWithDetails(@Param("customerId") Long customerId);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant r " +
            "JOIN FETCH r.owner " +
            "JOIN FETCH o.deliveryAddress " +
            "LEFT JOIN FETCH o.deliveryAgent " +
            "WHERE o.restaurant.id = :restaurantId " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByRestaurantIdWithDetails(@Param("restaurantId") Long restaurantId);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant r " +
            "JOIN FETCH r.owner " +
            "JOIN FETCH o.deliveryAddress " +
            "LEFT JOIN FETCH o.deliveryAgent " +
            "WHERE o.restaurant.id = :restaurantId AND o.status = :status " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByRestaurantAndStatusWithDetails(@Param("restaurantId") Long restaurantId, @Param("status") Order.OrderStatus status);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant r " +
            "JOIN FETCH r.owner " +
            "JOIN FETCH o.deliveryAddress " +
            "LEFT JOIN FETCH o.deliveryAgent " +
            "WHERE o.deliveryAgent.id = :agentId " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByDeliveryAgentIdWithDetails(@Param("agentId") Long agentId);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant r " +
            "JOIN FETCH r.owner " +
            "JOIN FETCH o.deliveryAddress " +
            "LEFT JOIN FETCH o.deliveryAgent " +
            "LEFT JOIN FETCH o.orderItems oi " +
            "LEFT JOIN FETCH oi.menuItem " +
            "LEFT JOIN FETCH o.payment " +
            "WHERE o.deliveryAgent.id = :agentId AND o.status IN :statuses " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByDeliveryAgentIdAndStatusIn(
            @Param("agentId") Long agentId,
            @Param("statuses") Collection<Order.OrderStatus> statuses);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant r " +
            "JOIN FETCH r.owner " +
            "JOIN FETCH o.deliveryAddress " +
            "LEFT JOIN FETCH o.deliveryAgent " +
            "LEFT JOIN FETCH o.orderItems oi " +
            "LEFT JOIN FETCH oi.menuItem " +
            "LEFT JOIN FETCH o.payment " +
            "WHERE o.deliveryAgent.id = :agentId AND o.status = :status " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByDeliveryAgentIdAndStatus(
            @Param("agentId") Long agentId,
            @Param("status") Order.OrderStatus status);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN FETCH o.customer " +
            "JOIN FETCH o.restaurant r " +
            "JOIN FETCH r.owner " +
            "JOIN FETCH o.deliveryAddress " +
            "LEFT JOIN FETCH o.deliveryAgent " +
            "LEFT JOIN FETCH o.orderItems oi " +
            "LEFT JOIN FETCH oi.menuItem " +
            "LEFT JOIN FETCH o.payment " +
            "WHERE o.status = :status " +
            "AND (o.deliveryAgent IS NULL OR o.deliveryAgent.id = :agentId) " +
            "ORDER BY o.createdAt ASC")
    List<Order> findAvailableDeliveriesForAgent(
            @Param("agentId") Long agentId,
            @Param("status") Order.OrderStatus status);

    @Query(SUMMARY_SELECT +
            "WHERE o.customer.id = :customerId ORDER BY o.createdAt DESC")
    Page<OrderSummaryResponse> findCustomerOrderSummaries(@Param("customerId") Long customerId, Pageable pageable);

    @Query(SUMMARY_SELECT +
            "WHERE o.restaurant.id = :restaurantId ORDER BY o.createdAt DESC")
    Page<OrderSummaryResponse> findRestaurantOrderSummaries(@Param("restaurantId") Long restaurantId, Pageable pageable);

    @Query(SUMMARY_SELECT +
            "WHERE o.deliveryAgent.id = :agentId ORDER BY o.createdAt DESC")
    Page<OrderSummaryResponse> findDeliveryAgentOrderSummaries(@Param("agentId") Long agentId, Pageable pageable);

    @Query(SUMMARY_SELECT +
            "WHERE o.customer.id = :customerId " +
            "AND (:cursorCreatedAt IS NULL OR o.createdAt < :cursorCreatedAt " +
            "OR (o.createdAt = :cursorCreatedAt AND o.id < :cursorId)) " +
            "ORDER BY o.createdAt DESC, o.id DESC")
    List<OrderSummaryResponse> findCustomerOrderSummariesAfterCursor(
            @Param("customerId") Long customerId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    @Query(SUMMARY_SELECT +
            "WHERE o.customer.id = :customerId " +
            "AND o.status = 'SCHEDULED' " +
            "ORDER BY o.scheduledAt ASC, o.createdAt ASC")
    Page<OrderSummaryResponse> findCustomerScheduledOrderSummaries(@Param("customerId") Long customerId, Pageable pageable);

    @Query(SUMMARY_SELECT +
            "WHERE o.customer.id = :customerId " +
            "AND o.status = 'SCHEDULED' " +
            "AND (:cursorCreatedAt IS NULL OR o.scheduledAt < :cursorCreatedAt " +
            "OR (o.scheduledAt = :cursorCreatedAt AND o.id < :cursorId)) " +
            "ORDER BY o.scheduledAt ASC, o.id ASC")
    List<OrderSummaryResponse> findCustomerScheduledOrderSummariesAfterCursor(
            @Param("customerId") Long customerId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    @Query(SUMMARY_SELECT +
            "WHERE o.restaurant.id = :restaurantId " +
            "AND (:cursorCreatedAt IS NULL OR o.createdAt < :cursorCreatedAt " +
            "OR (o.createdAt = :cursorCreatedAt AND o.id < :cursorId)) " +
            "ORDER BY o.createdAt DESC, o.id DESC")
    List<OrderSummaryResponse> findRestaurantOrderSummariesAfterCursor(
            @Param("restaurantId") Long restaurantId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    @Query(SUMMARY_SELECT +
            "WHERE o.deliveryAgent.id = :agentId " +
            "AND (:cursorCreatedAt IS NULL OR o.createdAt < :cursorCreatedAt " +
            "OR (o.createdAt = :cursorCreatedAt AND o.id < :cursorId)) " +
            "ORDER BY o.createdAt DESC, o.id DESC")
    List<OrderSummaryResponse> findDeliveryAgentOrderSummariesAfterCursor(
            @Param("agentId") Long agentId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    @Query(SUMMARY_SELECT +
            "WHERE o.restaurant.id = :restaurantId AND o.status = :status ORDER BY o.createdAt ASC")
    List<OrderSummaryResponse> findPendingSummariesForRestaurant(
            @Param("restaurantId") Long restaurantId,
            @Param("status") Order.OrderStatus status,
            Pageable pageable);

    @Query(SUMMARY_SELECT +
            "WHERE o.restaurant.id = :restaurantId AND o.status IN :statuses ORDER BY o.createdAt ASC")
    List<OrderSummaryResponse> findKitchenActiveSummaries(
            @Param("restaurantId") Long restaurantId,
            @Param("statuses") Collection<Order.OrderStatus> statuses,
            Pageable pageable);

    long countByCustomerId(Long customerId);

    // Add missing methods for Admin Service
    long countByCreatedAtAfter(LocalDateTime dateTime);
    long countByStatus(Order.OrderStatus status);
    long countByStatusAndCreatedAtAfter(Order.OrderStatus status, LocalDateTime dateTime);

    @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.status = 'DELIVERED'")
    Double sumTotalAmount();

    @Query("SELECT SUM(o.totalAmount) FROM Order o WHERE o.status = 'DELIVERED' AND o.createdAt >= :startDate")
    Double sumTotalAmountAfter(@Param("startDate") LocalDateTime startDate);

    List<Order> findTop10ByOrderByCreatedAtDesc();
    Page<Order> findByStatus(Order.OrderStatus status, Pageable pageable);

    List<Order> findByStatusAndScheduledAtLessThanEqual(Order.OrderStatus status, LocalDateTime scheduledAt);

    long countByCustomerIdAndStatus(Long customerId, Order.OrderStatus status);

    @Query("SELECT COALESCE(SUM(o.totalAmount + COALESCE(o.walletAmountUsed, 0)), 0) FROM Order o " +
            "WHERE o.customer.id = :customerId AND o.status = 'DELIVERED'")
    Double sumDeliveredSpendByCustomerId(@Param("customerId") Long customerId);

    long countByRestaurantId(Long restaurantId);

    long countByRestaurantIdAndCreatedAtAfter(Long restaurantId, LocalDateTime startDate);

    long countByRestaurantIdAndStatusAndCreatedAtAfter(
            Long restaurantId, Order.OrderStatus status, LocalDateTime startDate);

    @Query("SELECT COALESCE(SUM(o.totalAmount + o.walletAmountUsed), 0) FROM Order o " +
            "WHERE o.restaurant.id = :restaurantId AND o.status = 'DELIVERED' AND o.createdAt >= :startDate")
    Double sumRestaurantRevenueSince(@Param("restaurantId") Long restaurantId,
                                     @Param("startDate") LocalDateTime startDate);

    @Query("SELECT o.status, COUNT(o) FROM Order o " +
            "WHERE o.restaurant.id = :restaurantId AND o.createdAt >= :startDate GROUP BY o.status")
    List<Object[]> countRestaurantOrdersGroupedByStatus(@Param("restaurantId") Long restaurantId,
                                                        @Param("startDate") LocalDateTime startDate);

    // ---------------------------------------------------------------------
    // ETA accuracy (promised vs actual) — V17
    //
    // The promise lives in orders.estimated_delivery_at and the actual in
    // orders.delivered_at. OrderEtaSnapshot only records the promise side, so
    // accuracy cannot be derived from snapshots and is aggregated here instead.
    // All three queries are served by the V17 index
    // idx_order_eta_accuracy (delivered_at, estimated_delivery_at).
    // ---------------------------------------------------------------------

    /**
     * Number of deliveries in the window that can be scored, i.e. completed orders
     * that carry both a promised and an actual delivery timestamp. This is the
     * denominator of the on-time rate.
     *
     * @param since window start (inclusive), compared against {@code delivered_at}
     * @return measurable delivery count, never {@code null}
     */
    @Query("SELECT COUNT(o) FROM Order o " +
            "WHERE o.status = 'DELIVERED' AND o.deliveredAt >= :since AND o.estimatedDeliveryAt IS NOT NULL")
    long countMeasurableDeliveriesSince(@Param("since") LocalDateTime since);

    /**
     * Deliveries in the window that met or beat the promise. This is the numerator
     * of the on-time rate; a delivery landing exactly on the promised instant counts
     * as on time.
     *
     * @param since window start (inclusive), compared against {@code delivered_at}
     * @return on-time delivery count, never {@code null}
     */
    @Query("SELECT COUNT(o) FROM Order o " +
            "WHERE o.status = 'DELIVERED' AND o.deliveredAt >= :since " +
            "AND o.estimatedDeliveryAt IS NOT NULL AND o.deliveredAt <= o.estimatedDeliveryAt")
    long countOnTimeDeliveriesSince(@Param("since") LocalDateTime since);

    /**
     * Promised/actual timestamp pairs for late deliveries in the window, most recent
     * first, so the caller can average the overshoot in Java.
     *
     * <p>Minute arithmetic is deliberately kept out of the query: JPQL has no portable
     * {@code TIMESTAMPDIFF} and this project uses no vendor date functions anywhere.
     * The caller passes a {@link Pageable} to bound the sample size.
     *
     * @param since    window start (inclusive), compared against {@code delivered_at}
     * @param pageable sample cap and ordering slice
     * @return rows of {@code [estimatedDeliveryAt, deliveredAt]}, both non-null
     */
    @Query("SELECT o.estimatedDeliveryAt, o.deliveredAt FROM Order o " +
            "WHERE o.status = 'DELIVERED' AND o.deliveredAt >= :since " +
            "AND o.estimatedDeliveryAt IS NOT NULL AND o.deliveredAt > o.estimatedDeliveryAt " +
            "ORDER BY o.deliveredAt DESC")
    List<Object[]> findLateDeliveryTimestampsSince(@Param("since") LocalDateTime since, Pageable pageable);

    // ---------------------------------------------------------------------
    // Restaurant analytics aggregates (daily revenue + hourly volume).
    //
    // Implemented as native queries because:
    //  - JPQL has no portable DATE()/HOUR() functions
    //  - this project deliberately avoids vendor date functions elsewhere
    //  - a single grouped query replaces the prior per-day loop (N round-trips)
    // ---------------------------------------------------------------------

    /**
     * Per-day delivered-order totals for a restaurant over the window
     * {@code [startDate, now]}. Returns one row per day that has at least one
     * delivered order; the caller fills missing days with zeros.
     *
     * <p>Rows are {@code [java.sql.Date day, Long orderCount, Double revenue]}.
     *
     * @param restaurantId restaurant scope
     * @param startDate    inclusive lower bound on {@code created_at}
     * @return aggregate rows ordered by date ascending
     */
    @Query(value = "SELECT DATE(created_at) AS day, COUNT(*) AS cnt, " +
            "COALESCE(SUM(total_amount), 0) AS revenue " +
            "FROM orders " +
            "WHERE restaurant_id = :restaurantId " +
            "AND status = 'DELIVERED' " +
            "AND created_at >= :startDate " +
            "GROUP BY DATE(created_at) " +
            "ORDER BY DATE(created_at) ASC", nativeQuery = true)
    List<Object[]> findDailyDeliveredAggregates(@Param("restaurantId") Long restaurantId,
                                                @Param("startDate") LocalDateTime startDate);

    /**
     * Per-hour-of-day delivered-order counts for a restaurant over the window.
     * Used to surface peak hours without needing time-series storage.
     *
     * <p>Rows are {@code [Integer hourOfDay 0-23, Long orderCount]}.
     *
     * @param restaurantId restaurant scope
     * @param startDate    inclusive lower bound on {@code created_at}
     * @return aggregate rows ordered by hour ascending
     */
    @Query(value = "SELECT HOUR(created_at) AS hr, COUNT(*) AS cnt " +
            "FROM orders " +
            "WHERE restaurant_id = :restaurantId " +
            "AND status = 'DELIVERED' " +
            "AND created_at >= :startDate " +
            "GROUP BY HOUR(created_at) " +
            "ORDER BY HOUR(created_at) ASC", nativeQuery = true)
    List<Object[]> findHourlyDeliveredCounts(@Param("restaurantId") Long restaurantId,
                                             @Param("startDate") LocalDateTime startDate);

    /** Counts orders placed by a customer since the given timestamp (recovery/risk queries). */
    long countByCustomerIdAndCreatedAtAfter(Long customerId, java.time.LocalDateTime since);
}

package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface RestaurantSettlementRepository extends JpaRepository<RestaurantSettlement, Long> {
    List<RestaurantSettlement> findByRestaurantId(Long restaurantId);

    /** Distinct restaurants holding settlement rows in the given status (automation scan). */
    @Query("select distinct s.restaurantId from RestaurantSettlement s where s.status = :status")
    List<Long> findDistinctRestaurantIdsByStatus(@Param("status") String status);

    /** Sum of net amounts for a restaurant restricted to one status; never {@code null}. */
    @Query("select coalesce(sum(s.netAmount), 0) from RestaurantSettlement s "
            + "where s.restaurantId = :restaurantId and s.status = :status")
    BigDecimal sumNetAmountByRestaurantAndStatus(@Param("restaurantId") Long restaurantId,
                                                 @Param("status") String status);

    /**
     * Atomic PENDING→SETTLED flip guarded on the previous status: a settlement row
     * can only ever be settled once even under concurrent schedulers/instances,
     * which makes the automated batch idempotent without a cluster-wide lock.
     *
     * @return number of rows flipped (0 when a concurrent run already settled them)
     */
    @Modifying
    @Query("update RestaurantSettlement s set s.status = :to "
            + "where s.restaurantId = :restaurantId and s.status = :from")
    int atomicSettleByRestaurant(@Param("restaurantId") Long restaurantId,
                                 @Param("from") String from,
                                 @Param("to") String to);
}
package com.bhukkad.repository;

import com.bhukkad.entity.RestaurantSettlement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RestaurantSettlementRepository extends JpaRepository<RestaurantSettlement, Long> {
    boolean existsByOrderId(Long orderId);

    Page<RestaurantSettlement> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId, Pageable pageable);

    List<RestaurantSettlement> findByRestaurantIdAndStatus(Long restaurantId, RestaurantSettlement.SettlementStatus status);

    @Query("SELECT COALESCE(SUM(s.netAmount), 0) FROM RestaurantSettlement s WHERE s.restaurant.id = :restaurantId AND s.status = :status")
    Double sumNetAmountByRestaurantAndStatus(@Param("restaurantId") Long restaurantId,
                                             @Param("status") RestaurantSettlement.SettlementStatus status);

    @Query("SELECT COALESCE(SUM(s.netAmount), 0) FROM RestaurantSettlement s WHERE s.status = :status")
    Double sumNetAmountByStatus(@Param("status") RestaurantSettlement.SettlementStatus status);

    @Query("SELECT COUNT(s) FROM RestaurantSettlement s WHERE s.status = :status")
    long countByStatus(@Param("status") RestaurantSettlement.SettlementStatus status);
}

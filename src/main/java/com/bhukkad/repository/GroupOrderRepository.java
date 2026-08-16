package com.bhukkad.repository;

import com.bhukkad.entity.GroupOrder;
import com.bhukkad.entity.GroupOrder.GroupOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {

    @Query("SELECT go FROM GroupOrder go WHERE go.restaurant.id = :restaurantId AND go.status = :status ORDER BY go.createdAt DESC")
    List<GroupOrder> findByRestaurantAndStatus(@Param("restaurantId") Long restaurantId, @Param("status") GroupOrderStatus status);

    @Query(value = "SELECT * FROM group_orders WHERE JSON_CONTAINS(participating_customers, :customerId, '$') AND status = 'PENDING' LIMIT 1", nativeQuery = true)
    Optional<GroupOrder> findActiveByCustomer(@Param("customerId") Long customerId);

    @Query(value = "SELECT go FROM GroupOrder go WHERE go.primaryCustomerId = :customerId ORDER BY go.createdAt DESC")
    List<GroupOrder> findByPrimaryCustomer(@Param("customerId") Long customerId, Sort sort);

    @Query(value = "SELECT go FROM GroupOrder go WHERE go.status = 'DELIVERED' AND go.createdAt >= :startDate")
    List<GroupOrder> findDeliveredSince(@Param("startDate") LocalDateTime startDate);

    @Query(value = "SELECT COUNT(go) FROM GroupOrder go WHERE go.status = 'DELIVERED' AND go.createdAt >= :startDate")
    long countDeliveredSince(@Param("startDate") LocalDateTime startDate);

    @Query(value = "SELECT * FROM group_orders WHERE JSON_CONTAINS(participating_customers, :customerId, '$') AND status = 'PENDING' LIMIT 1", nativeQuery = true)
    Optional<GroupOrder> findPendingByParticipant(@Param("customerId") Long customerId);
}
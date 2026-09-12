package com.bhukkad.order.domain.repository;

import com.bhukkad.order.domain.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {

    List<SubscriptionPlan> findByUserId(Long userId);

    Optional<SubscriptionPlan> findByIdAndUserId(Long id, Long userId);

    @Query("SELECT p FROM SubscriptionPlan p "
            + "WHERE p.status = 'ACTIVE' "
            + "AND p.nextDeliveryDate IS NOT NULL AND p.nextDeliveryDate <= :date")
    List<SubscriptionPlan> findActivePlansDueOnOrBefore(@Param("date") LocalDate date);
}
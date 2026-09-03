package com.bhukkad.order.domain;

import com.bhukkad.order.domain.SubscriptionDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionDeliveryRepository extends JpaRepository<SubscriptionDelivery, Long> {

    Optional<SubscriptionDelivery> findByPlanIdAndScheduledDate(Long planId, LocalDate scheduledDate);

    List<SubscriptionDelivery> findByPlanIdOrderByScheduledDateDesc(Long planId);
}
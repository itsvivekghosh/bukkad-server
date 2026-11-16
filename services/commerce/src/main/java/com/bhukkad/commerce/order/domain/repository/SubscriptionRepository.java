package com.bhukkad.commerce.order.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import com.bhukkad.commerce.order.domain.entity.Subscription;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Page<Subscription> findByCustomerIdAndStatus(Long customerId, String status, Pageable pageable);
}
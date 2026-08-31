package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestaurantSettlementRepository extends JpaRepository<RestaurantSettlement, Long> {
    List<RestaurantSettlement> findByRestaurantId(Long restaurantId);
}
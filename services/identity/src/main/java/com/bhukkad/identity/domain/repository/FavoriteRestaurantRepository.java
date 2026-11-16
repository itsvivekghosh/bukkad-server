package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.FavoriteRestaurant;

import com.bhukkad.identity.domain.entity.FavoriteRestaurant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FavoriteRestaurantRepository extends JpaRepository<FavoriteRestaurant, Long> {
    Page<FavoriteRestaurant> findByCustomerId(Long customerId, Pageable pageable);

    /**
     * @deprecated Use {@link #findByCustomerId(Long, Pageable)} instead for bounded results.
     */
    @Deprecated
    List<FavoriteRestaurant> findByCustomerId(Long customerId);
    void deleteByCustomerIdAndRestaurantId(Long customerId, Long restaurantId);
}
package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.RestaurantOwner;

import com.bhukkad.identity.domain.entity.RestaurantOwner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RestaurantOwnerRepository extends JpaRepository<RestaurantOwner, Long> {
    Optional<RestaurantOwner> findByEmail(String email);

    boolean existsByEmail(String email);

    /** PERF-3: keyed duplicate check for owner registration (was a full findAll scan). */
    boolean existsByEmailIgnoreCase(String email);
}

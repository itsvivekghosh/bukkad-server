package com.bhukkad.repository;

import com.bhukkad.entity.RestaurantOwner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RestaurantOwnerRepository extends JpaRepository<RestaurantOwner, Long> {
    Optional<RestaurantOwner> findByEmail(String email);

    Optional<RestaurantOwner> findByPhoneNumber(String phoneNumber);

    Optional<RestaurantOwner> findByEmailOrPhoneNumber(String email, String phoneNumber);

    Boolean existsByEmail(String email);

    Boolean existsByPhoneNumber(String phoneNumber);

    org.springframework.data.domain.Page<RestaurantOwner> findByFullNameContainingOrEmailContaining(
            String fullName, String email, org.springframework.data.domain.Pageable pageable);
}
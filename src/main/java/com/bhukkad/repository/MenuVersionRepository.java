package com.bhukkad.repository;

import com.bhukkad.entity.MenuVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuVersionRepository extends JpaRepository<MenuVersion, Long> {

    Optional<MenuVersion> findTopByRestaurantIdOrderByVersionNumberDesc(Long restaurantId);

    List<MenuVersion> findByRestaurantIdOrderByVersionNumberDesc(Long restaurantId);

    List<MenuVersion> findByRestaurantIdAndStatus(Long restaurantId, MenuVersion.MenuVersionStatus status);
}
package com.bhukkad.repository;

import com.bhukkad.entity.InventoryAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryAlertRepository extends JpaRepository<InventoryAlert, Long> {

    List<InventoryAlert> findByRestaurantIdAndSentFalse(Long restaurantId);

    List<InventoryAlert> findByRestaurantIdAndAcknowledgedFalseAndSentTrue(Long restaurantId);

    Optional<InventoryAlert> findTopByRestaurantIdAndMenuItemIdAndTypeOrderByCreatedAtDesc(
            Long restaurantId, Long menuItemId, InventoryAlert.AlertType type);

    List<InventoryAlert> findByCreatedAtAfter(LocalDateTime since);
}
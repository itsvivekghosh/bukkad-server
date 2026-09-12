package com.bhukkad.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.bhukkad.restaurant.domain.entity.InventoryAlert;

public interface InventoryAlertRepository extends JpaRepository<InventoryAlert, Long> {
    List<InventoryAlert> findByMenuItemId(Long menuItemId);
    List<InventoryAlert> findByMenuItemIdIn(List<Long> menuItemIds);
    List<InventoryAlert> findByAlertType(String alertType);
}
package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryAlertRepository extends JpaRepository<InventoryAlert, Long> {
    List<InventoryAlert> findByMenuItemId(Long menuItemId);
    List<InventoryAlert> findByAlertType(String alertType);
}
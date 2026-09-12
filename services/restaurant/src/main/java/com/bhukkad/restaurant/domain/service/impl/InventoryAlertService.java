package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.restaurant.domain.entity.InventoryAlert;
import com.bhukkad.restaurant.domain.repository.InventoryAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Low-stock alerting (Batch C depth).
 */
@Service
@RequiredArgsConstructor
public class InventoryAlertService {

    private final InventoryAlertRepository alertRepository;

    @Transactional
    public InventoryAlert raise(Long menuItemId, int currentStock, int threshold) {
        String type = currentStock <= 0
                ? InventoryAlert.TYPE_OUT_OF_STOCK
                : InventoryAlert.TYPE_LOW_STOCK;
        InventoryAlert alert = new InventoryAlert();
        alert.setMenuItemId(menuItemId);
        alert.setCurrentStock(currentStock);
        alert.setThreshold(threshold);
        alert.setAlertType(type);
        return alertRepository.save(alert);
    }

    @Transactional(readOnly = true)
    public List<InventoryAlert> recent(Long menuItemId) {
        return alertRepository.findByMenuItemId(menuItemId);
    }
}

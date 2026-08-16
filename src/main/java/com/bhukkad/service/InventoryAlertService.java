package com.bhukkad.service;

import com.bhukkad.dto.response.InventoryAlertResponse;
import com.bhukkad.entity.InventoryAlert;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.InventoryAlertRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.util.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAlertService {

    private final InventoryAlertRepository inventoryAlertRepository;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;
    private final SecurityUtils securityUtils;
    private final NotificationHelper notificationHelper;

    @Value("${app.inventory.low-stock-alert-webhook:}")
    private String lowStockAlertWebhook;

    @Scheduled(fixedDelayString = "${app.inventory.alert-check-interval-ms:300000}")
    @Transactional
    public void checkLowStockItems() {
        List<Restaurant> restaurants = restaurantRepository.findAll();
        for (Restaurant restaurant : restaurants) {
            List<MenuItem> lowStockItems = menuItemRepository.findLowStockByRestaurant(restaurant.getId(), 10);
            for (MenuItem item : lowStockItems) {
                createAlertIfNeeded(item);
            }
        }
    }

    @Transactional
    public void createAlertIfNeeded(MenuItem menuItem) {
        Restaurant restaurant = menuItem.getCategory().getRestaurant();
        Integer stock = menuItem.getStockQuantity();
        int threshold = 10; // Default threshold

        if (stock == null || stock > threshold) {
            return;
        }

        InventoryAlert.AlertType alertType;
        if (stock == 0) {
            alertType = InventoryAlert.AlertType.OUT_OF_STOCK;
        } else if (stock < threshold * 0.2) {
            alertType = InventoryAlert.AlertType.CRITICAL_STOCK;
        } else {
            alertType = InventoryAlert.AlertType.LOW_STOCK;
        }

        // Check if we already have a recent alert for this item
        Optional<InventoryAlert> existingAlert = inventoryAlertRepository
                .findTopByRestaurantIdAndMenuItemIdAndTypeOrderByCreatedAtDesc(
                        restaurant.getId(), menuItem.getId(), alertType);

        if (existingAlert.isPresent()) {
            LocalDateTime lastAlert = existingAlert.get().getCreatedAt();
            if (lastAlert.isAfter(LocalDateTime.now().minusHours(4))) {
                return; // Already alerted within last 4 hours
            }
        }

        InventoryAlert alert = new InventoryAlert();
        alert.setRestaurant(restaurant);
        alert.setMenuItem(menuItem);
        alert.setType(alertType);
        alert.setCurrentStock(stock);
        alert.setThreshold(threshold);
        alert.setSent(false);

        inventoryAlertRepository.save(alert);
        sendAlertNotification(alert);

        log.info("Inventory alert created | restaurantId={} | menuItemId={} | type={} | stock={}",
                restaurant.getId(), menuItem.getId(), alertType, stock);
    }

    public List<InventoryAlertResponse> getAlertsByRestaurant(Long restaurantId) {
        return inventoryAlertRepository.findByRestaurantIdAndAcknowledgedFalseAndSentTrue(restaurantId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void acknowledgeAlert(Long alertId) {
        InventoryAlert alert = inventoryAlertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found"));

        verifyRestaurantOwnership(alert.getRestaurant().getId());
        alert.setAcknowledged(true);
        inventoryAlertRepository.save(alert);
    }

    private void sendAlertNotification(InventoryAlert alert) {
        try {
            String message = String.format(
                    "Low stock alert: %s is at %d units (threshold: %d)",
                    alert.getMenuItem().getName(),
                    alert.getCurrentStock(),
                    alert.getThreshold());

            if (lowStockAlertWebhook != null && !lowStockAlertWebhook.isBlank()) {
                notificationHelper.sendWebhookNotification(lowStockAlertWebhook, message);
            }

            alert.setSent(true);
            inventoryAlertRepository.save(alert);
        } catch (Exception ex) {
            log.warn("Failed to send inventory alert notification: {}", ex.getMessage());
        }
    }

    private void verifyRestaurantOwnership(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new ResourceNotFoundException("Not your restaurant");
        }
    }

    private InventoryAlertResponse toResponse(InventoryAlert alert) {
        return InventoryAlertResponse.builder()
                .id(alert.getId())
                .restaurantId(alert.getRestaurant().getId())
                .restaurantName(alert.getRestaurant().getName())
                .menuItemId(alert.getMenuItem().getId())
                .menuItemName(alert.getMenuItem().getName())
                .type(alert.getType())
                .currentStock(alert.getCurrentStock())
                .threshold(alert.getThreshold())
                .acknowledged(alert.getAcknowledged())
                .createdAt(alert.getCreatedAt())
                .build();
    }
}
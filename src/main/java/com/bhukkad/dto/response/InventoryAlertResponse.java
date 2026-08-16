package com.bhukkad.dto.response;

import com.bhukkad.entity.InventoryAlert;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAlertResponse {
    private Long id;
    private Long restaurantId;
    private String restaurantName;
    private Long menuItemId;
    private String menuItemName;
    private InventoryAlert.AlertType type;
    private Integer currentStock;
    private Integer threshold;
    private Boolean acknowledged;
    private LocalDateTime createdAt;
}
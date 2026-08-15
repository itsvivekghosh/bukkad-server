package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.inventory")
public class InventoryProperties {
    private int lowStockThreshold = 10;
}

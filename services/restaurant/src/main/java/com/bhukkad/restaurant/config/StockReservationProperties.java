package com.bhukkad.restaurant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.restaurant.stock-reservation")
public class StockReservationProperties {
    private boolean enabled = true;
    private long reservationTtlSeconds = 900;
}

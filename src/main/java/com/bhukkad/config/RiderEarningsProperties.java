package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.delivery.earnings")
public class RiderEarningsProperties {
    private double perDelivery = 30.0;
}

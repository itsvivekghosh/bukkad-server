package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.gift-card")
public class GiftCardProperties {
    private double minAmount = 100.0;
    private double maxAmount = 10000.0;
    private int validityDays = 365;
    private String notificationEmailEnabled = "false";
    private String senderEmail = "noreply@bhukkad.com";
}
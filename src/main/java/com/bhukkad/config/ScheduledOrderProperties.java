package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.scheduled-orders")
public class ScheduledOrderProperties {
    private int minimumLeadMinutes = 30;
    private int maxDaysAhead = 7;
}

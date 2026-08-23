package com.bhukkad.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.cache.stampede")
public class StampedeProperties {
    private boolean enabled = false;
    private int jitterPercent = 10;
}
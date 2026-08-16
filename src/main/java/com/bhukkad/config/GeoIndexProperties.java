package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.geo")
public class GeoIndexProperties {
    private boolean redisGeoEnabled = true;
    private String restaurantsGeoKey = "restaurants:geo";
}

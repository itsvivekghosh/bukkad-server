package com.bhukkad.personalization.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.recommendations")
public class RecommendationProperties {

    private int maxItems = 10;
    private int coOrderedItemLimit = 50;
    private int customerAffinityLimit = 25;
    private int topRestaurantsLimit = 3;
}

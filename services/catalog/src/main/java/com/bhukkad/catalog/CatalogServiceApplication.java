package com.bhukkad.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.bhukkad.catalog.personalization.config.RecommendationProperties;
import com.bhukkad.catalog.restaurant.config.ExperimentProperties;
import com.bhukkad.catalog.restaurant.config.StockReservationProperties;
import com.bhukkad.catalog.search.config.SearchFuzzyProperties;
import com.bhukkad.catalog.search.config.SearchSyncProperties;

/**
 * Catalog service — consolidated read-heavy catalog domain.
 * Owns restaurants (restaurants, menus, cuisines, availability),
 * search (unified search/autocomplete), and personalization (recommendations, feed ranking).
 * Consolidated from restaurant + search + personalization services.
 */
@SpringBootApplication(scanBasePackages = {"com.bhukkad.catalog", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.catalog", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.catalog", "com.bhukkad.common"})
@EnableJpaAuditing
@EnableConfigurationProperties({
        ExperimentProperties.class,
        RecommendationProperties.class,
        SearchFuzzyProperties.class,
        SearchSyncProperties.class,
        StockReservationProperties.class
})
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
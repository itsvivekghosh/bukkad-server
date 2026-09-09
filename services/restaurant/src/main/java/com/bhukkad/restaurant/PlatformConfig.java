package com.bhukkad.restaurant;

import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.restaurant.experiment.ExperimentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling // PERF-3: hosts the pod-local autocomplete index refresh (AutocompleteService)
@EnableConfigurationProperties({ExperimentProperties.class,
        com.bhukkad.restaurant.service.inventory.StockReservationProperties.class})
public class PlatformConfig {

    @Bean
    public OutboxClient outboxClient(OutboxEventRepository outboxEventRepository) {
        return new OutboxClient(outboxEventRepository);
    }
}

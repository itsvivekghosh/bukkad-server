package com.bhukkad.restaurant;

import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.common.outbox.OutboxEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the platform beans the service consumes (platform-lib is a library;
 * it does not auto-register beans).
 */
@Configuration
public class PlatformConfig {

    @Bean
    public OutboxClient outboxClient(OutboxEventRepository outboxEventRepository) {
        return new OutboxClient(outboxEventRepository);
    }
}

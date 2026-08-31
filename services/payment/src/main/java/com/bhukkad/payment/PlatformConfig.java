package com.bhukkad.payment;

import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.common.outbox.OutboxEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformConfig {

    @Bean
    public OutboxClient outboxClient(OutboxEventRepository outboxEventRepository) {
        return new OutboxClient(outboxEventRepository);
    }
}

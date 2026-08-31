package com.bhukkad.order;

import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.saga.SagaInstanceRepository;
import com.bhukkad.common.saga.SagaStepRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformConfig {

    @Bean
    public OutboxClient outboxClient(OutboxEventRepository outboxEventRepository) {
        return new OutboxClient(outboxEventRepository);
    }

    @Bean
    public SagaCoordinator sagaCoordinator(SagaInstanceRepository sagaInstanceRepository,
                                           SagaStepRepository sagaStepRepository) {
        return new SagaCoordinator(sagaInstanceRepository, sagaStepRepository);
    }
}

package com.bhukkad.social;

import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.social.config.PlatformConfig;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Test configuration for social integration tests.
 *
 * <p>Provides mock beans for external service dependencies that are not
 * available in the test environment.</p>
 */
@Configuration
@Import(PlatformConfig.class)
public class SocialIntegrationTestConfig {

    @MockBean
    private OutboxEventRepository outboxEventRepository;

    @Bean
    public OutboxClient outboxClient(OutboxEventRepository outboxEventRepository) {
        return new OutboxClient(outboxEventRepository);
    }
}

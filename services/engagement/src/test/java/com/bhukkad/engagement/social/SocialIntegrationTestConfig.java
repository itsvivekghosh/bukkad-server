package com.bhukkad.engagement.social;

import com.bhukkad.common.outbox.OutboxEventRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Test configuration for social integration tests.
 *
 * <p>Only mocks the outbox repository. This class used to also declare an
 * {@code outboxClient} bean and {@code @Import} social's {@code PlatformConfig}
 * — a self-conflict. It went unnoticed because {@code PlatformConfig} was not
 * component-scanned when social was its own service; inside the merged
 * engagement application both are picked up, so Spring rejected the duplicate
 * {@code outboxClient} definition. The production {@code PlatformConfig}
 * already provides that bean.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SocialIntegrationTestConfig {

    @MockBean
    private OutboxEventRepository outboxEventRepository;
}

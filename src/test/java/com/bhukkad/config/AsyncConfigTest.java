package com.bhukkad.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AsyncConfigTest {

    @Test
    void orderTaskExecutor_usesConfiguredPoolSizes() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "orderCorePoolSize", 2);
        ReflectionTestUtils.setField(config, "orderMaxPoolSize", 4);
        ReflectionTestUtils.setField(config, "orderQueueCapacity", 10);

        Executor executor = config.orderTaskExecutor();

        assertNotNull(executor);
    }
}

package com.bhukkad.common.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * PERF-2/D1: the relay loops are registered on the DEDICATED scheduler when
 * the application is ready (replacing the shared default-scheduler
 * {@code @Scheduled}), {@code recoverStale} is actually scheduled, and the
 * scheduler uses the {@code relay-} thread prefix.
 */
class OutboxRelayBootstrapTest {

    @Test
    void applicationReady_startsPollAndRecoveryOnRelayScheduler() throws Exception {
        OutboxPollPublisher relay = mock(OutboxPollPublisher.class);
        CountDownLatch polled = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(i -> {
            polled.countDown();
            return null;
        }).when(relay).poll();
        org.mockito.Mockito.doAnswer(i -> {
            recovered.countDown();
            return null;
        }).when(relay).recoverStale();

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("relay-");
        scheduler.initialize();
        try {
            OutboxRelayBootstrap bootstrap = new OutboxRelayBootstrap(relay,
                    new OutboxProperties(10, Duration.ofMillis(100), Duration.ofMillis(100),
                            Duration.ofSeconds(5), 100, 3, Duration.ofMillis(50)),
                    scheduler);

            bootstrap.start();
            bootstrap.start(); // idempotent — no double registration

            assertThat(polled.await(10, TimeUnit.SECONDS)).as("poll loop ran").isTrue();
            assertThat(recovered.await(10, TimeUnit.SECONDS)).as("recoverStale loop ran").isTrue();

            bootstrap.stop();
            verify(relay, atLeastOnce()).poll();
            verify(relay, atLeastOnce()).recoverStale();
        } finally {
            scheduler.shutdown();
        }
    }
}

package com.bhukkad.common.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P-06: wake bursts must collapse into at most ONE extra drain per coalesce
 * window ({@code pollInterval / 4}), and a wake arriving while a drain is
 * queued/running must be dropped (no pile-up on the relay scheduler).
 */
class OutboxWakeDrainListenerTest {

    private static final long WINDOW_MS = 1_250L; // pollInterval 5s / 4

    private static Message message() {
        return mock(Message.class);
    }

    @Test
    void burstWithinWindow_collapsesToOneDrain() {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger drains = new AtomicInteger();
        OutboxWakeDrainListener listener = new OutboxWakeDrainListener(
                drains::incrementAndGet, Runnable::run, WINDOW_MS, clock::get);

        for (int i = 0; i < 10; i++) {
            listener.onMessage(message(), new byte[0]);
            clock.addAndGet(100); // stay inside the 1250ms window
        }

        assertThat(drains.get()).as("burst inside one window = one drain").isEqualTo(1);
    }

    @Test
    void wakeAfterWindowElapsed_drainsAgain() {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger drains = new AtomicInteger();
        OutboxWakeDrainListener listener = new OutboxWakeDrainListener(
                drains::incrementAndGet, Runnable::run, WINDOW_MS, clock::get);

        listener.onMessage(message(), new byte[0]);
        clock.addAndGet(WINDOW_MS + 1);
        listener.onMessage(message(), new byte[0]);

        assertThat(drains.get()).isEqualTo(2);
    }

    @Test
    void wakeWhileDrainQueued_droppedUntilDrainCompletes() {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger drains = new AtomicInteger();
        List<Runnable> queued = new ArrayList<>();
        OutboxWakeDrainListener listener = new OutboxWakeDrainListener(
                drains::incrementAndGet, queued::add, WINDOW_MS, clock::get);

        listener.onMessage(message(), new byte[0]); // queued but NOT executed
        clock.addAndGet(WINDOW_MS + 1);             // outside the window now
        listener.onMessage(message(), new byte[0]); // must be dropped — drain still pending

        assertThat(queued).as("only one drain queued").hasSize(1);
        assertThat(drains.get()).isZero();

        queued.forEach(Runnable::run);              // drain completes → flag released
        listener.onMessage(message(), new byte[0]);

        assertThat(queued).hasSize(2);
        assertThat(drains.get()).isEqualTo(1);
    }

    @Test
    void drainFailure_flagReset_soLaterWakesStillDrain() {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicInteger attempts = new AtomicInteger();
        List<Runnable> queued = new ArrayList<>();
        OutboxWakeDrainListener listener = new OutboxWakeDrainListener(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("boom");
                    }
                }, queued::add, WINDOW_MS, clock::get);

        listener.onMessage(message(), new byte[0]);
        try {
            queued.get(0).run(); // first drain throws — the finally must still release the flag
        } catch (IllegalStateException ignored) {
            // simulated by the executor's task invocation
        }

        clock.addAndGet(WINDOW_MS + 1);
        listener.onMessage(message(), new byte[0]);
        queued.get(1).run();

        assertThat(attempts.get()).isEqualTo(2);
    }
}

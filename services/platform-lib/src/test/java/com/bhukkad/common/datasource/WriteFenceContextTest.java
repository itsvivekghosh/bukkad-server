package com.bhukkad.common.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-17 write-fence context: arms on the current thread at a clock instant,
 * reports active while age &lt; TTL, expires silently, and picks up
 * {@code app.datasource.replica.write-fence-ms} (default 2000) from the
 * {@link ReplicaRoutingConfig} binding.
 */
class WriteFenceContextTest {

    @AfterEach
    void restoreAmbientState() {
        WriteFenceContext.clear();
        WriteFenceContext.resetClock();
        WriteFenceContext.setWriteFenceMs(WriteFenceContext.DEFAULT_WRITE_FENCE_MS);
    }

    @Test
    void notArmed_neverActive() {
        assertThat(WriteFenceContext.isActive()).isFalse();
    }

    @Test
    void armed_withinTtl_isActive_thenExpires() {
        AtomicLong clock = new AtomicLong(1000);
        WriteFenceContext.setClock(clock::get);
        WriteFenceContext.setWriteFenceMs(2000);

        WriteFenceContext.arm();

        assertThat(WriteFenceContext.isActive()).isTrue();

        clock.set(1000 + 1999); // age 1999 < 2000
        assertThat(WriteFenceContext.isActive()).isTrue();

        clock.set(1000 + 2000); // age == TTL → expired
        assertThat(WriteFenceContext.isActive()).isFalse();

        clock.set(1000 + 10_000);
        assertThat(WriteFenceContext.isActive()).isFalse();
    }

    @Test
    void clear_removesTheFence() {
        WriteFenceContext.setClock(() -> 42L);
        WriteFenceContext.arm();

        WriteFenceContext.clear();

        assertThat(WriteFenceContext.isActive()).isFalse();
    }

    @Test
    void nonPositiveTtl_ignored() {
        WriteFenceContext.setWriteFenceMs(0);
        assertThat(WriteFenceContext.getWriteFenceMs())
                .as("a zero/negative fence would disable the guard — keep the previous value")
                .isEqualTo(WriteFenceContext.DEFAULT_WRITE_FENCE_MS);
    }

    @Test
    void config_bindsWriteFenceMsProperty() {
        new ApplicationContextRunner()
                .withUserConfiguration(ReplicaRoutingConfig.class)
                .withPropertyValues("app.datasource.replica.write-fence-ms=5000")
                .run(context ->
                        assertThat(WriteFenceContext.getWriteFenceMs()).isEqualTo(5000L));
    }

    @Test
    void config_defaultWriteFenceMs_whenPropertyAbsent() {
        WriteFenceContext.setWriteFenceMs(9999); // prove the default really resets
        new ApplicationContextRunner()
                .withUserConfiguration(ReplicaRoutingConfig.class)
                .run(context ->
                        assertThat(WriteFenceContext.getWriteFenceMs())
                                .isEqualTo(WriteFenceContext.DEFAULT_WRITE_FENCE_MS));
    }
}

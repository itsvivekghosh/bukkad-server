package com.bhukkad.common.tenant;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link TenantContext}: the tenant id is a {@link ThreadLocal},
 * so it must not leak between threads sharing a thread pool.
 */
class TenantContextTest {

    @Test
    void tenantIdIsIsolatedAcrossThreads() throws Exception {
        TenantContext.setTenantId("tenant-a");
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> observedOnOtherThread = new AtomicReference<>();

            Thread other = new Thread(() -> {
                // ThreadLocals are not inherited by child threads.
                assertThat(TenantContext.getTenantId()).isNull();
                TenantContext.setTenantId("tenant-b");
                observedOnOtherThread.set(TenantContext.getTenantId());
                latch.countDown();
            });
            other.start();
            latch.await();

            // The original thread still sees its own tenant id.
            assertThat(TenantContext.getTenantId()).isEqualTo("tenant-a");
            assertThat(observedOnOtherThread.get()).isEqualTo("tenant-b");
        } finally {
            TenantContext.clearTenantId();
        }
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void clearTenantIdRemovesBinding() {
        TenantContext.setTenantId("tenant-a");
        try {
            assertThat(TenantContext.hasTenantId()).isTrue();
        } finally {
            TenantContext.clearTenantId();
        }
        assertThat(TenantContext.hasTenantId()).isFalse();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void currentTenantIdentifierResolverDelegatesToThreadLocal() {
        TenantContext context = new TenantContext();
        TenantContext.setTenantId("tenant-a");
        try {
            assertThat(context.resolveCurrentTenantIdentifier()).isEqualTo("tenant-a");
            assertThat(context.validateExistingCurrentSessions()).isTrue();
        } finally {
            TenantContext.clearTenantId();
        }
        assertThat(context.resolveCurrentTenantIdentifier()).isNull();
        assertThat(context.validateExistingCurrentSessions()).isFalse();
    }
}

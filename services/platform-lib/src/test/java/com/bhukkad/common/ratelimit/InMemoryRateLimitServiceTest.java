package com.bhukkad.common.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimitServiceTest {

    private final InMemoryRateLimitService service = new InMemoryRateLimitService();

    @Test
    void allowsWithinLimit() {
        RateLimitDecision d1 = service.check("order-create", "user-1", 3, 60);
        RateLimitDecision d2 = service.check("order-create", "user-1", 3, 60);
        RateLimitDecision d3 = service.check("order-create", "user-1", 3, 60);

        assertThat(d1.allowed()).isTrue();
        assertThat(d2.allowed()).isTrue();
        assertThat(d3.allowed()).isTrue();
    }

    @Test
    void deniesBeyondLimit() {
        for (int i = 0; i < 3; i++) {
            assertThat(service.check("order-create", "user-1", 3, 60).allowed()).isTrue();
        }
        RateLimitDecision denied = service.check("order-create", "user-1", 3, 60);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive();
    }

    @Test
    void differentIdentifiers_areIndependent() {
        for (int i = 0; i < 5; i++) {
            service.check("order-create", "user-a", 3, 60);
        }
        assertThat(service.check("order-create", "user-b", 3, 60).allowed()).isTrue();
    }

    @Test
    void differentBuckets_areIndependent() {
        for (int i = 0; i < 5; i++) {
            service.check("order-create", "user-a", 3, 60);
        }
        assertThat(service.check("search", "user-a", 3, 60).allowed()).isTrue();
    }

    @Test
    void windowExpiresAndResets() {
        // limit 1, window 1s — first call allowed, second denied
        assertThat(service.check("bucket", "id", 1, 1).allowed()).isTrue();
        assertThat(service.check("bucket", "id", 1, 1).allowed()).isFalse();

        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(service.check("bucket", "id", 1, 1).allowed()).isTrue();
    }

    @Test
    void limitOfOne_secondCallDenied() {
        assertThat(service.check("b", "id", 1, 60).allowed()).isTrue();
        assertThat(service.check("b", "id", 1, 60).allowed()).isFalse();
    }
}

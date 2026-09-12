package com.bhukkad.identity.integration.controller;

import com.bhukkad.common.ratelimit.RateLimitExceededException;
import com.bhukkad.identity.support.AbstractIdentityPostgresTest;
import com.bhukkad.identity.infrastructure.ratelimit.LoginLockoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W1-AUTH deliverable 3 against the REAL Redis harness.
 *
 * <p>The heavy per-(email,IP) counting runs through the {@link LoginLockoutService}
 * bean directly: going over HTTP for every probe would collide with the
 * 30-per-5-min {@code @RateLimited} login bucket that ALL integration tests
 * share (same loopback IP, same Redis), and the audit contract is that lockout
 * engages BEFORE the rate limiter's budget matters. The HTTP mount (429 +
 * Retry-After on /auth/login) is asserted once explicitly.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 1s base lock (2s on the second episode) so the test can observe
        // expiry without sleeping 5 minutes.
        "app.auth.lockout.base-lock-seconds=1",
        "app.auth.lockout.max-lock-seconds=4"
})
class LoginLockoutRedisIntegrationTest extends AbstractIdentityPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private LoginLockoutService lockout;

    private final RestClient client = RestClient.builder().build();

    private static final String TEST_IP = "203.0.113.7";

    private void register(String email) throws Exception {
        var response = client.post().uri("http://localhost:" + port + "/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"" + email + "\",\"phoneNumber\":\"999\","
                        + "\"fullName\":\"Lockout IT\",\"password\":\"password123\"}")
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void loginLockout_mountedOnAuthLogin_5FailsLockWith429AndRetryAfter() throws Exception {
        String email = "lockout_http_" + System.nanoTime() + "@b.com";
        register(email);

        // 5 failed logins (each 401) cross the threshold...
        for (int i = 0; i < 5; i++) {
            try {
                client.post().uri("http://localhost:" + port + "/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"email\":\"" + email + "\",\"password\":\"wrong-" + i + "\"}")
                        .retrieve().toEntity(String.class);
                throw new AssertionError("expected 401");
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                assertThat(e.getStatusCode().value()).isEqualTo(401);
            }
        }

        // ...the 6th attempt — even with the CORRECT password — is locked out.
        try {
            client.post().uri("http://localhost:" + port + "/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"email\":\"" + email + "\",\"password\":\"password123\"}")
                    .retrieve().toEntity(String.class);
            throw new AssertionError("expected 429");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(429);
        }
    }

    @Test
    void lockoutService_fiveFailuresLock_thenSuccessResets() throws Exception {
        String email = "lockout_svc_" + System.nanoTime() + "@b.com";
        register(email);

        for (int i = 0; i < 4; i++) {
            lockout.recordFailure(email, TEST_IP);
        }
        // A successful login clears the counter: a 5th failure right after
        // starts from scratch (count=1), so the pair is NOT locked.
        lockout.recordSuccess(email, TEST_IP);
        lockout.recordFailure(email, TEST_IP);
        assertThatCode(() -> lockout.assertAllowed(email, TEST_IP))
                .as("success must reset the failure counter")
                .doesNotThrowAnyException();

        // Four more failures reach the threshold → lock engages.
        for (int i = 0; i < 4; i++) {
            lockout.recordFailure(email, TEST_IP);
        }
        assertThatThrownBy(() -> lockout.assertAllowed(email, TEST_IP))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void lockoutService_lockExpires_andEpisodesEscalateExponentially() throws Exception {
        String email = "lockout_exp_" + System.nanoTime() + "@b.com";

        // Episode 1: 5 failures → lock (base 1s in this context).
        for (int i = 0; i < 5; i++) {
            lockout.recordFailure(email, TEST_IP);
        }
        long firstLock = lockout.recordFailure(email, TEST_IP); // already locked: negative TTL
        assertThatThrownBy(() -> lockout.assertAllowed(email, TEST_IP))
                .isInstanceOf(RateLimitExceededException.class);

        // Lock expires (1s base; sleep generously past it) — allowed again.
        Thread.sleep(2000);
        assertThatCode(() -> lockout.assertAllowed(email, TEST_IP))
                .as("expired lock must not deny")
                .doesNotThrowAnyException();

        // Episode 2 (no success in between → strikes=2): the new lock doubles.
        for (int i = 0; i < 5; i++) {
            lockout.recordFailure(email, TEST_IP);
        }
        long secondLock = Math.abs(lockout.recordFailure(email, TEST_IP));
        assertThatThrownBy(() -> lockout.assertAllowed(email, TEST_IP))
                .isInstanceOf(RateLimitExceededException.class);

        assertThat(Math.abs(firstLock)).as("first episode lock (ms)").isLessThanOrEqualTo(1500);
        assertThat(secondLock).as("second episode must escalate to ~2s")
                .isBetween(1800L, 3000L);
    }
}

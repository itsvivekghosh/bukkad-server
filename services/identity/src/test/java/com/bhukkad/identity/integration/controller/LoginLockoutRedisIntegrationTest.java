package com.bhukkad.identity.integration.controller;

import com.bhukkad.common.ratelimit.RateLimitExceededException;
import com.bhukkad.identity.infrastructure.ratelimit.LoginLockoutService;
import com.bhukkad.identity.support.AbstractIdentityPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

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
        // 10s base lock: comfortably outlives the sub-second round-trips of the
        // 5 failures + the 6th locked probe, yet keeps the escalation test's
        // between-episodes expiry wait short.
        "app.auth.lockout.base-lock-seconds=10",
        "app.auth.lockout.max-lock-seconds=60"
})
class LoginLockoutRedisIntegrationTest extends AbstractIdentityPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private LoginLockoutService lockout;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * MUST stay retry-free. With httpclient5 on the test classpath, a plain
     * {@code RestClient.builder().build()} auto-detects HttpComponents, whose
     * {@code DefaultHttpRequestRetryStrategy} retries 429/503 responses
     * (idempotent or not!) after waiting out {@code Retry-After}. On a 30s
     * lockout that transparently re-sent the locked attempt exactly when the
     * lock expired and turned the 429 assertion into a flaky 200. The stock
     * JDK client only retries transport-level failures and never re-sends a
     * complete error response — the posture an assertion needs.
     */
    private final RestClient client = RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(10))
                            .build()))
            .build();

    private static final String TEST_IP = "203.0.113.7";

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private void register(String email) {
        var response = client.post().uri(baseUrl() + "/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"" + email + "\",\"phoneNumber\":\"999\","
                        + "\"fullName\":\"Lockout IT\",\"password\":\"password123\"}")
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void loginLockout_mountedOnAuthLogin_5FailsLockWith429AndRetryAfter() {
        String email = "lockout_http_" + System.nanoTime() + "@b.com";
        register(email);
        String lockKey = "bhukkad:auth:lockout:lock:" + email + "|127.0.0.1";

        // 5 failed logins (each 401) cross the threshold...
        for (int i = 0; i < 5; i++) {
            try {
                client.post().uri(baseUrl() + "/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"email\":\"" + email + "\",\"password\":\"wrong-" + i + "\"}")
                        .retrieve().toEntity(String.class);
                throw new AssertionError("expected 401 on attempt " + (i + 1));
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                assertThat(e.getStatusCode().value()).isEqualTo(401);
            }
        }

        // ...the 6th attempt — even with the CORRECT password — is locked out.
        assertThatThrownBy(() -> client.post()
                .uri(baseUrl() + "/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"password123\"}")
                .retrieve().toEntity(String.class))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class)
                .satisfies(t -> {
                    var e = (org.springframework.web.client.HttpClientErrorException) t;
                    assertThat(e.getStatusCode().value()).isEqualTo(429);
                    String retryAfter = e.getResponseHeaders().getFirst("Retry-After");
                    assertThat(retryAfter).isNotBlank();
                    assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
                });

        // The lock survived the denied attempt (429 never reaches recordSuccess).
        assertThat(redisTemplate.getExpire(lockKey)).isGreaterThan(0);
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
        String lockKey = "bhukkad:auth:lockout:lock:" + email + "|" + TEST_IP;

        // Episode 1: 5 failures → lock (base 10s in this context).
        for (int i = 0; i < 5; i++) {
            lockout.recordFailure(email, TEST_IP);
        }
        long firstLock = lockout.recordFailure(email, TEST_IP); // already locked: negative TTL
        assertThatThrownBy(() -> lockout.assertAllowed(email, TEST_IP))
                .isInstanceOf(RateLimitExceededException.class);

        // Verify the first lock TTL is approximately baseLockSeconds (10s).
        long firstLockTtl = Math.abs(firstLock);
        assertThat(firstLockTtl).as("first episode lock TTL (ms)").isBetween(9000L, 11000L);

        // Episode 2 must start AFTER the lock expires: while locked,
        // recordFailure short-circuits on the active lock and never accrues
        // strikes, so escalation only becomes observable past the horizon.
        awaitLockExpiry(lockKey);
        for (int i = 0; i < 5; i++) {
            lockout.recordFailure(email, TEST_IP);
        }
        long secondLock = Math.abs(lockout.recordFailure(email, TEST_IP));

        // strikes=2 → the new lock doubles the base.
        assertThat(secondLock).as("second episode must escalate to ~2x base")
                .isBetween(19000L, 21500L);
        assertThatThrownBy(() -> lockout.assertAllowed(email, TEST_IP))
                .isInstanceOf(RateLimitExceededException.class);
    }

    private void awaitLockExpiry(String lockKey) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline) {
            Long ttl = redisTemplate.getExpire(lockKey);
            if (ttl == null || ttl < 0) {
                return;
            }
            Thread.sleep(250);
        }
        org.junit.jupiter.api.Assertions.fail("lock key did not expire within 25s: " + lockKey);
    }
}

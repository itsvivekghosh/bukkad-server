package com.bhukkad.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validates platform JWTs (HS256 shared secret or RS256 via JWKS).
 *
 * <p>Production hardening notes:
 * <ul>
 *   <li>The JWKS cache is STALE-WHILE-REFRESH (audit V-03): verify() never
 *       performs blocking HTTP once warm. An expired entry is served while a
 *       single-flight async refresh (one worker, {@link #refreshInProgress}
 *       CAS gate) fetches the new key set; fetch failures back off for
 *       {@link #FAILURE_BACKOFF_MILLIS} so a down IdP is not hammered every
 *       request. Only a true cold start (no keys ever fetched) blocks — and
 *       even that is bounded by the RestClient's connect 2 s / read 3 s.
 *       The cache is pre-warmed on {@link ApplicationReadyEvent}.</li>
 *   <li>On an unknown {@code kid} (key rotation) a refresh is scheduled —
 *       rate-limited by {@link #UNKNOWN_KID_REFRESH_MIN_MILLIS} — so new keys
 *       are picked up within seconds instead of at cache TTL expiry.</li>
 *   <li>A failed JWKS refresh keeps serving the last good key set (stale-read
 *       beats fail-closed for availability; signatures still verified).</li>
 *   <li>The HMAC verifier is built ONCE at construction; in prod/staging a
 *       configured-but-too-short secret fails the BOOT instead of turning the
 *       first service-mesh token into a runtime 500 (audit V-15).</li>
 *   <li>ADR-004 dual-key grace: identity now issues RS256 and serves JWKS;
 *       this validator prefers the JWKS path and STILL accepts legacy HS256
 *       tokens signed with the shared secret while
 *       {@code app.auth.jwt.hmac-grace-enabled} is true (default). Flipping
 *       that property to false rejects HS256 outright. {@code alg=none} and
 *       HS384/HS512 downgrade attempts are always rejected.</li>
 *   <li>P1 REVOCATION: when a {@link JwtRevocationService} bean exists, an
 *       access token whose {@code iat} precedes the account's
 *       revoked-before epoch is rejected — logout/password-change stops the
 *       15-minute grace without waiting for TTL. A Redis failure FAILS OPEN
 *       (request accepted) and increments {@code jwt_revocation_check_bypass};
 *       no bean (auth-less slice contexts) = pre-P1 behaviour, no lookups.</li>
  * </ul>
 */
@Component
public class PlatformJwtValidator {

    private static final Logger log = LoggerFactory.getLogger(PlatformJwtValidator.class);
    private static final long JWKS_TTL_MILLIS = 60 * 60 * 1000L;
    /** Minimum spacing between refreshes triggered by unknown kids. */
    private static final long UNKNOWN_KID_REFRESH_MIN_MILLIS = 30 * 1000L;
    /** A failed JWKS fetch is retried no sooner than this (V-03 failure backoff). */
    private static final long FAILURE_BACKOFF_MILLIS = 30 * 1000L;
    private static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration JWKS_READ_TIMEOUT = Duration.ofSeconds(3);

    /** jjwt/HS256 minimum: 256 bits of secret material (RFC 7518 §3.2). */
    static final int MIN_HMAC_SECRET_BYTES = 32;

    /**
     * P1 REVOCATION: incremented every time a Redis epoch read fails and the
     * check is bypassed (fail-open — availability beats a hard 401 storm
     * during a Redis outage, and the bypass must be observable).
     */
    static final String METRIC_REVOCATION_BYPASS = "jwt_revocation_check_bypass";

    private final PlatformJwtProperties properties;
    private final RestClient restClient;
    @Nullable
    private final MeterRegistry meterRegistry;
    /**
     * Login/credential-change access-token revocation store; {@code null} when
     * no {@link JwtRevocationService} bean exists (auth-less slice contexts) —
     * the validator then behaves exactly as before P1: revocation-unaware.
     */
    @Nullable
    private final JwtRevocationService revocationService;
    /** Pre-built HS256 verifier — NEVER per-request; null = secret absent/too short in a non-strict profile. */
    @Nullable
    private final MACVerifier macVerifier;


    private volatile JWKSet cachedJwks;
    private volatile long lastFetchMillis;
    private volatile long lastFailedFetchMillis;
    private volatile long lastUnknownKidRefreshMillis;

    /** Single-flight gate: at most one async JWKS refresh runs at a time. */
    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
    /** Cold-start serialization: one blocking fetch for all waiting threads. */
    private final Object initialFetchLock = new Object();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "jwks-refresh");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public PlatformJwtValidator(PlatformJwtProperties properties,
                                Environment environment,
                                ObjectProvider<MeterRegistry> meterRegistryProvider,
                                ObjectProvider<JwtRevocationService> revocationServiceProvider) {
        this(properties, defaultRestClient(),
                properties.requiredInProfile(environment), meterRegistryProvider.getIfAvailable(),
                revocationServiceProvider == null ? null : revocationServiceProvider.getIfAvailable());
    }

    /** Convenience for non-Spring construction (tests): permissive profile, no metrics. */
    public PlatformJwtValidator(PlatformJwtProperties properties) {
        this(properties, defaultRestClient(), false, null, null);
    }

    /** Test seam: injected transport, permissive profile check, no metrics. */
    PlatformJwtValidator(PlatformJwtProperties properties, RestClient restClient) {
        this(properties, restClient, false, null, null);
    }

    PlatformJwtValidator(PlatformJwtProperties properties, RestClient restClient,
                         boolean requiredInProfile, @Nullable MeterRegistry meterRegistry) {
        this(properties, restClient, requiredInProfile, meterRegistry, null);
    }

    /** P1 REVOCATION test seam: validator with an explicit revocation store. */
    PlatformJwtValidator(PlatformJwtProperties properties, RestClient restClient,
                         boolean requiredInProfile, @Nullable MeterRegistry meterRegistry,
                         @Nullable JwtRevocationService revocationService) {
        this.properties = properties;
        this.restClient = restClient;
        this.meterRegistry = meterRegistry;
        this.revocationService = revocationService;
        byte[] secretBytes = properties.secret() == null
                ? new byte[0] : properties.secret().getBytes(StandardCharsets.UTF_8);
        this.macVerifier = buildMacVerifier(secretBytes, requiredInProfile);
    }


    /**
     * V-15: build the HMAC key exactly once; a configured-but-weak secret in a
     * strict profile is a boot failure, not a first-request runtime 500.
     */
    @Nullable
    private static MACVerifier buildMacVerifier(byte[] secretBytes, boolean requiredInProfile) {
        if (secretBytes.length == 0) {
            return null; // JWKS-only configuration (or auth disabled) — nothing to build.
        }
        if (secretBytes.length < MIN_HMAC_SECRET_BYTES) {
            if (requiredInProfile) {
                throw new IllegalStateException("app.auth.jwt.secret must be at least "
                        + MIN_HMAC_SECRET_BYTES + " UTF-8 bytes in prod/staging but is "
                        + secretBytes.length + " (audit V-15)");
            }
            log.warn("Platform JWT HMAC secret is only {} bytes (<{}); HS256 tokens will be rejected "
                    + "instead of risking a weak key (audit V-15)", secretBytes.length, MIN_HMAC_SECRET_BYTES);
            return null;
        }
        try {
            return new MACVerifier(secretBytes);
        } catch (JOSEException e) {
            if (requiredInProfile) {
                throw new IllegalStateException("app.auth.jwt.secret is not usable as an HMAC key: "
                        + e.getMessage(), e);
            }
            log.warn("Could not build platform JWT HMAC verifier: {}", e.getMessage());
            return null;
        }
    }

    static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) JWKS_CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) JWKS_READ_TIMEOUT.toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }

    @PreDestroy
    void shutdownRefreshExecutor() {
        refreshExecutor.shutdownNow();
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    /** V-03 pre-warm: fetch the key set before the first authenticated request. */
    @EventListener(ApplicationReadyEvent.class)
    public void prewarmJwksCache() {
        if (StringUtils.hasText(properties.jwksUrl()) && cachedJwks == null) {
            scheduleRefresh();
        }
    }

    public Optional<TokenPrincipal> validate(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!verifySignature(jwt)) {
                recordRejection("signature");
                log.debug("JWT rejected: invalid signature");
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                recordRejection("expired");
                log.debug("JWT rejected: missing or past expiration");
                return Optional.empty();
            }
            if (claims.getNotBeforeTime() != null
                    && claims.getNotBeforeTime().toInstant().isAfter(Instant.now())) {
                recordRejection("not_yet_valid");
                log.debug("JWT rejected: not yet valid");
                return Optional.empty();
            }
            if (StringUtils.hasText(properties.issuer())
                    && !properties.issuer().equals(claims.getIssuer())) {
                recordRejection("issuer");
                log.debug("JWT rejected: unexpected issuer");
                return Optional.empty();
            }
            if (StringUtils.hasText(properties.audience())
                    && !claims.getAudience().contains(properties.audience())) {
                recordRejection("audience");
                log.debug("JWT rejected: unexpected audience");
                return Optional.empty();
            }
            if (claims.getSubject() == null) {
                recordRejection("subject");
                log.debug("JWT rejected: missing subject");
                return Optional.empty();
            }
            long userId;
            try {
                userId = Long.parseLong(claims.getSubject());
            } catch (NumberFormatException e) {
                recordRejection("subject");
                log.debug("JWT rejected: subject is not numeric");
                return Optional.empty();
            }
            // P1 REVOCATION: the subject survived every signature/claims step
            // — this is the user's ACCESS token. Reject anything issued before
            // the user's last logout / credential change / deactivation epoch.
            if (revocationService != null && isRevokedAccess(userId, claims)) {
                recordRejection("revoked");
                log.debug("JWT rejected: issued before the account's revocation epoch (userId={})", userId);
                return Optional.empty();
            }
            return Optional.of(new TokenPrincipal(userId,
                    claims.getStringClaim("email"),
                    scopeOf(claims)));
        } catch (Exception e) {
            recordRejection("malformed");
            log.debug("JWT validation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Revocation-epoch comparison. Fails OPEN when the epoch store is
     * unreachable: an outage may not turn every authenticated request into a
     * 401, but the bypass must be observable
     * ({@link #METRIC_REVOCATION_BYPASS}).
     */
    private boolean isRevokedAccess(long userId, JWTClaimsSet claims) {
        try {
            Instant revokedBefore = revocationService.revokedBefore(userId);
            if (revokedBefore == null) {
                return false;
            }
            Instant issuedAt = claims.getIssueTime() == null
                    ? null : claims.getIssueTime().toInstant();
            if (issuedAt == null) {
                // No iat to compare against — nothing to revoke by epoch; the
                // existing exp/nbf checks still bound this token.
                return false;
            }
            return issuedAt.isBefore(revokedBefore);
        } catch (Exception e) {
            recordRevocationBypass(e);
            return false;
        }
    }

    /** Fail-open bookkeeping: a Redis error must bypass the check, visibly. */
    private void recordRevocationBypass(Exception e) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_REVOCATION_BYPASS).increment();
        }
        log.warn("JWT revocation check failed, token accepted without it (fail-open): {}", e.getMessage());
    }


    /**
     * ADR-004: identity now stamps {@code role} alongside the legacy
     * {@code scope} claim; validators prefer {@code scope} and fall back to
     * {@code role} so a token carrying only the new claim still authenticates.
     */
    private static String scopeOf(JWTClaimsSet claims) throws java.text.ParseException {
        String scope = claims.getStringClaim("scope");
        if (scope == null) {
            scope = claims.getStringClaim("role");
        }
        return scope;
    }

    /**
     * Dual-key verification (ADR-004 step 2): RS256 tokens verify via the JWKS
     * key set; legacy HS256 tokens verify against the shared secret while the
     * HMAC grace window is open. Everything else — the unsigned
     * {@code alg=none} and the HS384/HS512 downgrade attempts from the audit
     * V-15 matrix included — is rejected without a fallback path.
     */
    private boolean verifySignature(SignedJWT jwt) throws Exception {
        String algName = jwt.getHeader().getAlgorithm() == null
                ? "" : jwt.getHeader().getAlgorithm().getName();
        boolean jwksConfigured = StringUtils.hasText(properties.jwksUrl());
        if (JWSAlgorithm.RS256.getName().equals(algName)) {
            if (!jwksConfigured) {
                log.debug("JWT rejected: RS256 token but no JWKS URL configured");
                return false;
            }
            return verifyRsa(jwt);
        }
        if (JWSAlgorithm.HS256.getName().equals(algName)) {
            if (jwksConfigured && !properties.hmacGrace()) {
                log.debug("JWT rejected: HS256 token after the HMAC grace window was closed");
                return false;
            }
            if (macVerifier != null) {
                return jwt.verify(macVerifier);
            }
            log.debug("JWT rejected: HS256 token without a shared secret (downgrade guard)");
            return false;
        }
        log.debug("JWT rejected: unsupported alg={} (downgrade guard)", algName);
        return false;
    }

    private boolean verifyRsa(SignedJWT jwt) throws Exception {
        JWKSet jwks = jwksCache();
        String kid = jwt.getHeader().getKeyID();
        JWK jwk = jwks.getKeyByKeyId(kid);
        if (!(jwk instanceof RSAKey rsaKey)) {
            // Unknown kid usually means the IdP rotated keys; trigger one
            // (rate-limited) refresh so rotation converges in seconds instead
            // of waiting for the cache TTL. The triggering request itself is
            // rejected with the current keys — later requests see the swap.
            if (StringUtils.hasText(kid) && refreshOnUnknownKid()) {
                scheduleRefresh();
                jwk = cachedJwks == null ? null : cachedJwks.getKeyByKeyId(kid);
            }
            if (!(jwk instanceof RSAKey rsaKeyAfterRefresh)) {
                log.debug("JWT rejected: no matching RSA key for kid={}", kid);
                return false;
            }
            return jwt.verify(new RSASSAVerifier(rsaKeyAfterRefresh));
        }
        return jwt.verify(new RSASSAVerifier(rsaKey));
    }

    private boolean refreshOnUnknownKid() {
        long now = System.currentTimeMillis();
        if (now - lastUnknownKidRefreshMillis < UNKNOWN_KID_REFRESH_MIN_MILLIS) {
            return false;
        }
        lastUnknownKidRefreshMillis = now;
        return true;
    }

    /**
     * Stale-while-revalidate lookup (audit V-03). Never blocks once at least
     * one key set is cached; blocking HTTP happens ONLY on a cold start.
     */
    private JWKSet jwksCache() throws Exception {
        JWKSet current = cachedJwks;
        long now = System.currentTimeMillis();
        if (current != null && now - lastFetchMillis < JWKS_TTL_MILLIS) {
            return current; // warm
        }
        if (current != null) {
            if (now - lastFailedFetchMillis >= FAILURE_BACKOFF_MILLIS) {
                scheduleRefresh(); // serve stale, refresh in the background
            }
            return current;
        }
        return blockingInitialFetch(); // ONLY the cold path blocks
    }

    /**
     * Cold start: concurrent verifiers queue on one monitor and the first
     * performs a single bounded fetch; the rest find the cache populated.
     */
    private JWKSet blockingInitialFetch() throws Exception {
        synchronized (initialFetchLock) {
            JWKSet existing = cachedJwks;
            if (existing != null) {
                return existing;
            }
            // Propagates — the caller maps it to a validation rejection, and
            // the next request retries (bounded by the RestClient timeouts).
            fetchJwksBlocking();
            return cachedJwks;
        }
    }

    /** Schedule a single-flight async refresh; a no-op while one is in flight. */
    private void scheduleRefresh() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        refreshExecutor.execute(() -> {
            try {
                fetchJwksBlocking();
            } catch (Exception e) {
                log.warn("Async JWKS refresh failed, serving stale keys: {}", e.getMessage());
            } finally {
                refreshInProgress.set(false);
            }
        });
    }

    /**
     * Fetch, parse and swap; updates success/failure timestamps and metrics.
     * On failure with a cached key set the stale set keeps being served;
     * on a cold failure the exception propagates to the caller.
     */
    private void fetchJwksBlocking() throws Exception {
        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            String body = restClient.get().uri(properties.jwksUrl()).retrieve().body(String.class);
            JWKSet parsed = JWKSet.parse(body);
            if (parsed.getKeys().isEmpty()) {
                throw new IllegalStateException("JWKS endpoint returned an empty key set");
            }
            cachedJwks = parsed;
            lastFetchMillis = System.currentTimeMillis();
        } catch (Exception e) {
            outcome = "failure";
            lastFailedFetchMillis = System.currentTimeMillis();
            if (cachedJwks != null) {
                // Serve the last good keys rather than rejecting every
                // request during an IdP hiccup; the backoff gate throttles retries.
                log.warn("JWKS refresh failed, serving stale keys: {}", e.getMessage());
                return;
            }
            throw e;
        } finally {
            recordRefreshMetrics(outcome, System.nanoTime() - startNanos);
        }
    }

    private void recordRefreshMetrics(String outcome, long durationNanos) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("jwks_refresh_duration")
                .description("Platform JWKS fetch duration (audit V-03)")
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        meterRegistry.counter("jwks_refresh_outcome", "outcome", outcome).increment();
    }

    /** Every validation rejection is observable (audit V-15/G-2). */
    private void recordRejection(String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter("service_auth_rejected", "reason", reason).increment();
        }
    }

    /** Test hook: force the cached key set to be treated as expired. */
    void expireJwksCacheForTests() {
        this.lastFetchMillis = 0L;
        this.lastFailedFetchMillis = 0L;
    }
}

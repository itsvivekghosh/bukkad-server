package com.bhukkad.identity.service;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.JwtRevocationService;
import com.bhukkad.identity.domain.RefreshToken;
import com.bhukkad.identity.domain.RefreshTokenRepository;
import com.bhukkad.identity.security.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token session store (audit H4-1/H4-2): 256-bit secure-random
 * bearer tokens persisted ONLY as SHA-256 hex hashes (a DB leak cannot mint
 * sessions), rotated on every use. Rotation stays inside one family with the
 * original absolute {@code expiresAt} ({@code app.jwt.refresh-ttl-days}) —
 * refreshing never extends a session. Presenting an already-revoked token
 * revokes the WHOLE family (reuse detection): whichever copy the attacker or
 * the legitimate client holds, both die.
 *
 * <p>P1 REVOCATION: every session kill made through this service
 * ({@link #revoke(String)} per-device logout, {@link #revokeAllForCustomer(Long)}
 * full logout / password change / reset / deactivation) additionally stamps
 * the user's {@link JwtRevocationService} access-token epoch — closing the
 * "bearer access token lives to its 15-minute TTL after logout" gap. The
 * epoch write is best-effort (a Redis outage degrades to the TTL-bound
 * behaviour, it never fails the DB-side revocation).</p>
 *
 * <p>Violations surface as the generic 401 {@link UnauthorizedException}:
 * unknown, revoked, expired and raced tokens are deliberately
 * indistinguishable to the caller.</p>
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private final TransactionTemplate tx;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256-bit
    private static final String GENERIC_REJECTION = "Invalid refresh token";

    private final RefreshTokenRepository repository;
    private final JwtProperties jwtProperties;
    /**
     * Optional by deployment shape (slice contexts may carry no Redis epoch
     * store): resolved per revocation; absent = TTL-bounded behaviour only.
     */
    private final ObjectProvider<JwtRevocationService> jwtRevocations;


    /** Outcome of a successful rotation: who, and the new raw refresh token. */
    public record Rotation(Long customerId, String refreshToken, String deviceId, Instant expiresAt) {
    }

    /** Mints a brand-new session (login / register / legacy migration) in its own family. */
    @Transactional
    public String issue(Long customerId, String userAgent, String deviceId) {
        String raw = randomToken();
        RefreshToken row = RefreshToken.of(customerId, sha256Hex(raw),
                Instant.now().plus(Duration.ofDays(jwtProperties.refreshTtlDays())));
        row.setFamilyId(UUID.randomUUID().toString());
        row.setUserAgent(truncate(userAgent, 512));
        row.setDeviceId(truncate(deviceId, 64));
        repository.save(row);
        return raw;
    }

    /**
     * Consumes the presented token and rotates it: revokes the old row,
     * stores a new one in the SAME family with the SAME absolute expiry.
     *
     * @throws UnauthorizedException token unknown, revoked (→ whole family is
     *                               revoked too), expired, or lost a
     *                               concurrent double-submit race
     */
    /**
     * The revoke-on-reuse/kill-family updates MUST survive the
     * {@link UnauthorizedException} rollback of rotate's own transaction —
     * otherwise (found in its own integration test) a revoked presentation
     * rejects the request while silently leaving live siblings of the stolen
     * family usable. Propagation.REQUIRES_NEW commits the kill immediately.
     */
    private int killFamily(String familyId) {
        TransactionTemplate requiresNew = new TransactionTemplate(tx.getTransactionManager());
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int[] killed = new int[1];
        requiresNew.executeWithoutResult(status ->
                killed[0] = repository.revokeAllByFamily(familyId, Instant.now()));
        return killed[0];
    }

    @Transactional
    public Rotation rotate(String presentedRaw, String userAgent, String deviceId) {
        Instant now = Instant.now();
        String hash = sha256Hex(presentedRaw);
        RefreshToken presented = repository.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException(GENERIC_REJECTION));

        if (presented.getRevokedAt() != null) {
            int killed = killFamily(presented.getFamilyId());
            log.warn("REFRESH_TOKEN_REUSE customerId={} family={} revokedRows={}",
                    presented.getCustomerId(), presented.getFamilyId(), killed);
            throw new UnauthorizedException(GENERIC_REJECTION);
        }
        if (!presented.isActiveAt(now)) {
            throw new UnauthorizedException(GENERIC_REJECTION); // expired: keep it as it is
        }
        // Conditional UPDATE: the concurrent second presenter of the same
        // token sees 0 rows and is treated as a reuse attempt.
        if (repository.revokeIfLive(hash, now) == 0) {
            int killed = killFamily(presented.getFamilyId());
            log.warn("REFRESH_TOKEN_DOUBLE_SPEND customerId={} family={} revokedRows={}",
                    presented.getCustomerId(), presented.getFamilyId(), killed);
            throw new UnauthorizedException(GENERIC_REJECTION);
        }

        String raw = randomToken();
        RefreshToken next = RefreshToken.of(presented.getCustomerId(), sha256Hex(raw),
                presented.getExpiresAt()); // absolute horizon: rotation never extends it
        next.setFamilyId(presented.getFamilyId());
        next.setUserAgent(truncate(userAgent != null ? userAgent : presented.getUserAgent(), 512));
        next.setDeviceId(truncate(deviceId != null ? deviceId : presented.getDeviceId(), 64));
        repository.save(next);
        return new Rotation(presented.getCustomerId(), raw, next.getDeviceId(), next.getExpiresAt());
    }

    /**
     * Single-session logout: revokes exactly this token; unknown tokens are
     * accepted no-ops. P1 REVOCATION: the owning account's ACCESS-token epoch
     * is stamped too, so a bearer token issued before this logout stops
     * working immediately instead of running out its TTL. Epoch revocation is
     * account-wide (JWTs carry no device binding): on a per-device logout the
     * user's other sessions lose only their current access tokens — they
     * recover silently via the next refresh-token rotation.
     */
    @Transactional
    public void revoke(String presentedRaw) {
        if (presentedRaw == null || presentedRaw.isBlank()) {
            return;
        }
        repository.findByTokenHash(sha256Hex(presentedRaw)).ifPresent(row -> {
            repository.revokeIfLive(row.getTokenHash(), Instant.now());
            revokeAccessEpoch(row.getCustomerId());
        });
    }

    /**
     * Kills every live session of a principal (password change / reset), the
     * no-token logout variant, and accounts found deactivated at refresh time.
     * Also stamps the epoch so outstanding access tokens die with it
     * (P1 REVOCATION — see class javadoc).
     */
    @Transactional
    public int revokeAllForCustomer(Long customerId) {
        int killed = repository.revokeAllByCustomer(customerId, Instant.now());
        revokeAccessEpoch(customerId);
        if (killed > 0) {
            log.info("REFRESH_TOKENS_REVOKED_ALL customerId={} revokedRows={}", customerId, killed);
        }
        return killed;
    }

    /** Best-effort access-token epoch stamp; never breaks the DB revocation. */
    private void revokeAccessEpoch(Long customerId) {
        if (customerId == null) {
            return;
        }
        JwtRevocationService revocations = jwtRevocations.getIfAvailable();
        if (revocations != null) {
            revocations.revoke(customerId, Instant.now());
        }
    }


    private static String randomToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** SHA-256 hex digest — the same storage format as password_reset_tokens. */
    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}

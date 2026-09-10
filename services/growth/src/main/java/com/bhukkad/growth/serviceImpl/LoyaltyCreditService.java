package com.bhukkad.growth.serviceImpl;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.growth.api.LoyaltyDailyCapExceededException;
import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.repository.LoyaltyCreditIdempotencyRepository;
import com.bhukkad.growth.repository.LoyaltyPointsLedgerRepository;
import com.bhukkad.growth.service.LoyaltyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Orchestration for the {@code POST /customers/{id}/loyalty/credit} surface
 * (ADR-005 / audit feature #4): idempotency key (scope {@code LOYALTY_CREDIT})
 * + per-customer daily credit cap + the one-transaction ledger credit.
 *
 * <p>The whole flow runs in a single transaction: the idempotency claim
 * (unique {@code (scope, key)} first-write-wins), the ledger append and the
 * atomic balance upsert all commit — or none of it does. A concurrent
 * duplicate therefore cannot double-credit and a crash mid-way cannot
 * half-credit.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyCreditService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final LoyaltyService loyaltyService;
    private final LoyaltyCreditIdempotencyRepository idempotencyRepository;
    private final LoyaltyPointsLedgerRepository ledgerRepository;
    private final GrowthProperties growthProperties;

    /**
     * Idempotent credit. Returns the outcome to report to the caller: a
     * fresh credit or a replay of an already-processed key.
     */
    @Transactional
    public CreditOutcome credit(Long customerId, int points, String reason, String idempotencyKey) {
        Optional<IdempotencyRecord> existing = idempotencyRepository.findByKey(idempotencyKey);
        if (existing.isPresent()) {
            return CreditOutcome.replayed();
        }

        assertWithinDailyCap(customerId, points);

        // The (scope, key) unique constraint is the authoritative guard: if a
        // concurrent duplicate claimed the key between our read and this
        // insert, 0 rows means we must not credit either — abort the whole tx.
        int claimed = idempotencyRepository.insertIfAbsent(idempotencyKey, customerId,
                com.bhukkad.common.idempotency.IdempotencyRecord.IdempotencyStatus.COMPLETED.name(),
                "{\"customerId\":%d,\"points\":%d}".formatted(customerId, points),
                java.time.LocalDateTime.now().plus(IDEMPOTENCY_TTL));
        if (claimed == 0) {
            throw new DuplicateRequestException(
                    "LOYALTY_CREDIT already in progress for idempotency key " + idempotencyKey);
        }

        String ledgerReference = ledgerReferenceFor(idempotencyKey);
        loyaltyService.creditPoints(customerId, points, reason, ledgerReference);
        return CreditOutcome.credited();
    }

    /** Caller-facing result of a credit call (fresh vs replay). */
    public record CreditOutcome(boolean freshCredit, boolean replay) {
        public static CreditOutcome credited() {
            return new CreditOutcome(true, false);
        }

        public static CreditOutcome replayed() {
            return new CreditOutcome(false, true);
        }
    }

    /** Abuse ceiling: a customer may only be granted so many points per day. */
    private void assertWithinDailyCap(Long customerId, int points) {
        long cap = growthProperties.getLoyalty().getDailyCreditCapPoints();
        long creditedToday = ledgerRepository.creditedSince(
                customerId, LocalDate.now().atStartOfDay());
        if (creditedToday + points > cap) {
            log.warn("Loyalty credit cap hit customerId={} creditedToday={} requested={} cap={}",
                    customerId, creditedToday, points, cap);
            throw new LoyaltyDailyCapExceededException(
                    "Daily credit cap of %d points exceeded for customer %d".formatted(cap, customerId));
        }
    }

    /** reference_id is varchar(50): full keys fit, longer keys collapse to a stable digest. */
    private String ledgerReferenceFor(String idempotencyKey) {
        if (idempotencyKey.length() <= 50) {
            return idempotencyKey;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(idempotencyKey.getBytes(StandardCharsets.UTF_8)), 0, 25);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every supported JDK.
            throw new IllegalStateException(e);
        }
    }
}

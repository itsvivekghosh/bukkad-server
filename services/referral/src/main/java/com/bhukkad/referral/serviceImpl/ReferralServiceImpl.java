package com.bhukkad.referral.serviceImpl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.ratelimit.RateLimitDecision;
import com.bhukkad.common.ratelimit.RateLimitExceededException;
import com.bhukkad.common.ratelimit.RateLimitService;
import com.bhukkad.referral.config.ReferralAbuseProperties;
import com.bhukkad.referral.config.ReferralServiceProperties;
import com.bhukkad.referral.dto.response.ReferralInfoResponse;
import com.bhukkad.referral.entity.ReferralRewardLedger;
import com.bhukkad.referral.entity.UserReferralCode;
import com.bhukkad.referral.repository.ReferralRewardLedgerRepository;
import com.bhukkad.referral.repository.UserReferralCodeRepository;
import com.bhukkad.referral.service.ReferralService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Personal referral codes and rewards summaries — the SINGLE code generator
 * (ADR-005): growth and identity both delegate here.
 *
 * <p>Integrity rules enforced here (audit feature #4):</p>
 * <ul>
 *   <li><b>Collision-safe generation</b> — a random tail over the full
 *   A-Z0-9 alphabet, retried on conflict with a growing (unbounded) tail
 *   length. There is no {@code id % 10000} / timestamp-modulo fallback and
 *   no check-then-insert race: the conditional insert/update is the authority
 *   and {@code uk_referral_code} is the backstop.</li>
 *   <li><b>Idempotent apply</b> — an early-return guard when
 *   {@code referredBy} is already set, plus the V10 partial unique index
 *   {@code uq_user_referral_codes_referred_by}; a concurrent re-bind is
 *   reported as already-bound.</li>
 *   <li><b>Exactly-once rewards</b> — every credited reward lands as an
 *   append-only {@link ReferralRewardLedger} row keyed by
 *   {@code (event_type, event_id)} (e.g. {@code REFERRAL_APPLY /
 *   apply:<customerId>} or {@code REFERRAL_COMPLETE / order:<orderId>}), so
 *   replayed events can never double-credit.</li>
 * </ul>
 */
@Service
public class ReferralServiceImpl implements ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralServiceImpl.class);

    private static final String CODE_PREFIX = "BK";
    /** Full alphabet tail (codes are machine-compared, no lookalike exclusions). */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int MIN_TAIL_LENGTH = 8;
    private static final int MAX_GENERATION_ATTEMPTS = 25;
    /** referral_code is varchar(40); keep "BK" + 10 random chars inside it. */
    private static final int CODE_TAIL_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserReferralCodeRepository referralCodeRepository;
    private final ReferralRewardLedgerRepository rewardLedgerRepository;
    private final ReferralServiceProperties properties;
    private final RateLimitService rateLimitService;
    private final ReferralAbuseProperties abuseProperties;

    public ReferralServiceImpl(UserReferralCodeRepository referralCodeRepository,
                               ReferralRewardLedgerRepository rewardLedgerRepository,
                               ReferralServiceProperties properties,
                               RateLimitService rateLimitService,
                               ReferralAbuseProperties abuseProperties) {
        this.referralCodeRepository = referralCodeRepository;
        this.rewardLedgerRepository = rewardLedgerRepository;
        this.properties = properties;
        this.rateLimitService = rateLimitService;
        this.abuseProperties = abuseProperties;
    }

    @Override
    @Transactional
    public ReferralInfoResponse getReferralInfo(Long customerId) {
        if (customerId == null) {
            throw new BusinessException("Customer id is required");
        }
        UserReferralCode referral = findByCustomerIdOrCreate(customerId);
        if (!StringUtils.hasText(referral.getReferralCode())) {
            throw new BusinessException("Referral code not assigned");
        }
        long referralsCount = referralCodeRepository.countByReferredBy(customerId);
        return new ReferralInfoResponse(
                referral.getReferralCode(),
                (int) referralsCount,
                referral.getReferralBonusEarned());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isValidReferralCode(String code) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return referralCodeRepository.findByReferralCode(normalize(code)).isPresent();
    }

    @Override
    @Transactional
    public String generateAndSaveReferralCode(Long customerId) {
        if (customerId == null) {
            throw new BusinessException("Customer id is required");
        }
        UserReferralCode referral = findByCustomerIdOrCreate(customerId);
        if (!StringUtils.hasText(referral.getReferralCode())) {
            assignUniqueCode(customerId);
            referral = referralCodeRepository.findByCustomerId(customerId)
                    .orElseThrow(() -> new BusinessException("Referral record disappeared"));
        }
        return referral.getReferralCode();
    }

    /**
     * Applies a referral code to a new customer. Idempotent: a customer that
     * already carries {@code referredBy} is never re-bound (early return,
     * {@code firstBinding=false}); a concurrent re-bind reports the same.
     */
    @Override
    @Transactional
    public ApplyReferralOutcome applyReferral(Long newCustomerId, String customerEmail, String referralCodeInput) {
        if (newCustomerId == null || !StringUtils.hasText(referralCodeInput)) {
            return ApplyReferralOutcome.notApplied();
        }
        // Abuse ceiling (audit feature #4): per-customer fixed-window limit on
        // the apply surface, enforced by the atomic limiter regardless of caller.
        RateLimitDecision decision = rateLimitService.check(
                "referral-apply", "customer:" + newCustomerId,
                abuseProperties.getApplyPerCustomerPerDay(), 86_400);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    "Too many referral applications. Try again later.", decision.retryAfterSeconds());
        }
        findByCustomerIdOrCreate(newCustomerId);

        UserReferralCode referrer = referralCodeRepository
                .findByReferralCode(normalize(referralCodeInput))
                .orElseThrow(() -> new BusinessException("Invalid referral code"));
        if (referrer.getCustomerId().equals(newCustomerId)) {
            throw new BusinessException("Cannot use your own referral code");
        }

        int bound = referralCodeRepository.bindReferrer(newCustomerId, referrer.getCustomerId());
        if (bound == 0) {
            // Early-return guard: already referred (or bound between our read
            // and this statement). Never re-bind, never re-reward.
            Long existingReferrer = referralCodeRepository.findByCustomerId(newCustomerId)
                    .map(UserReferralCode::getReferredBy)
                    .orElse(null);
            log.info("Referral apply rejected: customer {} already bound to referrer {}",
                    newCustomerId, existingReferrer);
            return ApplyReferralOutcome.alreadyBound(existingReferrer);
        }

        // Atomic referrer-counter bump (no read-modify-write lost update).
        referralCodeRepository.incrementReferralsCount(referrer.getCustomerId());

        creditApplyRewardOnce(referrer.getCustomerId(), newCustomerId);
        log.info("Referral applied | customerId={} | referrerCustomerId={}",
                newCustomerId, referrer.getCustomerId());
        return ApplyReferralOutcome.firstBinding(referrer.getCustomerId());
    }

    @Override
    @Transactional
    public CompletionOutcome completeReferral(Long referredCustomerId, Long orderId) {
        if (referredCustomerId == null) {
            throw new BusinessException("Customer id is required");
        }
        UserReferralCode referee = referralCodeRepository.findByCustomerId(referredCustomerId)
                .orElseThrow(() -> new BusinessException("Customer has no referral record"));
        if (referee.getReferredBy() == null) {
            return CompletionOutcome.noBinding();
        }
        String eventId = "order:" + (orderId != null ? orderId : referredCustomerId);
        double completionBonus = properties.getCompletionBonusAmount();
        int credited = completionBonus > 0
                ? rewardLedgerRepository.insertRewardIfAbsent(referee.getReferredBy(), referredCustomerId,
                        ReferralRewardLedger.TYPE_COMPLETION_BONUS, completionBonus,
                        ReferralRewardLedger.EVENT_COMPLETE, eventId, orderId)
                : 0;
        if (credited == 1) {
            rewardLedgerRepository.addBonusEarned(referee.getReferredBy(), completionBonus);
            log.info("Referral completed | customerId={} | referrer={} | orderId={}",
                    referredCustomerId, referee.getReferredBy(), orderId);
            return CompletionOutcome.completed(referee.getReferredBy());
        }
        log.info("Referral completion replay ignored | customerId={} | eventId={}", referredCustomerId, eventId);
        return CompletionOutcome.alreadyCompleted();
    }

    /** One APPLY_BONUS ledger row per referee, ever; replays and races are no-ops. */
    private void creditApplyRewardOnce(Long referrerId, Long newCustomerId) {
        String eventId = "apply:" + newCustomerId;
        double applyBonus = properties.getApplyBonusAmount();
        int credited = applyBonus > 0
                ? rewardLedgerRepository.insertRewardIfAbsent(referrerId, newCustomerId,
                        ReferralRewardLedger.TYPE_APPLY_BONUS, applyBonus,
                        ReferralRewardLedger.EVENT_APPLY, eventId, null)
                : 0;
        if (credited == 1) {
            rewardLedgerRepository.addBonusEarned(referrerId, applyBonus);
        }
    }

    /**
     * Finds (or atomically creates) the customer's referral row, assigning a
     * collision-safe code. Rows created by the conditional insert have no
     * in-memory counterpart, so callers re-read after creation.
     */
    private UserReferralCode findByCustomerIdOrCreate(Long customerId) {
        return referralCodeRepository.findByCustomerId(customerId)
                .orElseGet(() -> {
                    createWithUniqueCode(customerId);
                    return referralCodeRepository.findByCustomerId(customerId)
                            .orElseThrow(() -> new BusinessException("Referral record creation failed"));
                });
    }

    private void createWithUniqueCode(Long customerId) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            if (referralCodeRepository.insertReferralRow(customerId, newCode(tailLengthFor(attempt)), null) == 1) {
                return;
            }
            log.debug("Referral code collision on create, retrying | customerId={} | attempt={}", customerId, attempt);
        }
        throw new BusinessException("Could not generate a unique referral code");
    }

    private void assignUniqueCode(Long customerId) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            if (referralCodeRepository.assignCode(customerId, newCode(tailLengthFor(attempt))) == 1) {
                return;
            }
            log.debug("Referral code collision on assign, retrying | customerId={} | attempt={}", customerId, attempt);
        }
        throw new BusinessException("Could not generate a unique referral code");
    }

    /**
     * Random tail over the full A-Z0-9 alphabet. The tail length grows every
     * five collisions (unbounded space), so the retry loop always terminates
     * in practice — there is deliberately no {@code id % 10000} fallback.
     */
    private int tailLengthFor(int attempt) {
        return CODE_TAIL_LENGTH + (attempt / 5) * 4;
    }

    private String newCode(int length) {
        StringBuilder tail = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            tail.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return (CODE_PREFIX + tail).toUpperCase(Locale.ROOT);
    }

    private String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}

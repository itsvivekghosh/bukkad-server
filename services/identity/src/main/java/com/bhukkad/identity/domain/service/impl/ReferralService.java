package com.bhukkad.identity.domain.service.impl;

import com.bhukkad.identity.api.WalletCreditPort;
import com.bhukkad.identity.api.dto.response.ReferralInfoResponse;
import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.repository.CustomerRepository;
import com.bhukkad.identity.infrastructure.client.ReferralServiceClient;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.ratelimit.RateLimitDecision;
import com.bhukkad.common.ratelimit.RateLimitExceededException;
import com.bhukkad.common.ratelimit.RateLimitService;
import com.bhukkad.identity.api.WalletCreditPort;
import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.repository.CustomerRepository;
import com.bhukkad.identity.api.dto.response.ReferralInfoResponse;
import com.bhukkad.identity.domain.service.impl.ReferralProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;

/**
 * Port of the monolith {@code com.bhukkad.referral.ReferralService} (WAVE 2).
 * Refers to identity-owned customer state only.
 *
 * <p>ADR-005 (audit feature #4): identity is NO LONGER a code generator —
 * the "BK + id + modulo tail" local generation is deleted and code
 * creation/binding is delegated to the referral module's internal API via
 * {@link ReferralServiceClient} (service-JWT on the {@code X-Service-Token}
 * header). The public identity contract is unchanged: {@link
 * #initializeNewCustomer} still assigns {@code customer.referralCode} and
 * still links {@code referredById} for display/IDOR checks.</p>
 *
 * <p>Wallet crediting (the monolith referrer/referee bonus) goes through the
 * narrow {@link WalletCreditPort}; the in-process
 * {@link DeferredWalletCreditAdapter} logs instead of crediting until the
 * wallet domain is extracted — the referral module now owns the durable
 * reward ledger (exactly-once) for its own bonuses.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    private final CustomerRepository customerRepository;
    private final WalletCreditPort walletCreditPort;
    private final ReferralProperties referralProperties;
    private final RateLimitService rateLimitService;
    private final ReferralServiceClient referralServiceClient;

    /**
     * Assigns a referral code to a new customer and, when enabled, links the
     * referrer relationship.
     *
     * <p>Deliberately does <em>not</em> save the customer: the caller owns the
     * persistence (registration already saved the customer and keeps the entity
     * managed inside its transaction, so these mutations are flushed with the
     * same commit). This avoids a redundant second INSERT/UPDATE round-trip.</p>
     *
     * <p>When the referral service is unreachable the registration flow still
     * succeeds with a locally-issued display code (never persisted as the
     * referral module's code of record); the next code call re-issues the
     * canonical code idempotently.</p>
     */
    @Transactional
    public void initializeNewCustomer(Customer customer, String referralCodeInput) {
        customer.setReferralCode(generateCode(customer));
        if (referralProperties.isEnabled() && StringUtils.hasText(referralCodeInput)) {
            applyReferral(customer, referralCodeInput.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Transactional(readOnly = true)
    public ReferralInfoResponse getReferralInfo(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException("Customer not found"));
        if (!StringUtils.hasText(customer.getReferralCode())) {
            throw new BusinessException("Referral code not assigned");
        }
        long referralsCount = customerRepository.countByReferredById(customerId);
        double bonusEarned = walletCreditPort.referralBonusEarned(customerId);
        return ReferralInfoResponse.builder()
                .referralCode(customer.getReferralCode())
                .referralsCount((int) referralsCount)
                .referralBonusEarned(bonusEarned)
                .build();
    }

    /**
     * Lightweight in-process rate guard for referral actions. Throws when the
     * caller exceeds the configured per-source allowance for the bucket.
     */
    public boolean isValidReferralCode(String code) {
        return StringUtils.hasText(code) && customerRepository.findByReferralCode(code).isPresent();
    }

    public void assertNotRateLimited(String key) {
        RateLimitDecision decision = rateLimitService.check("referral", key, 10L, 60);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    "Too many requests. Try again later.", decision.retryAfterSeconds());
        }
    }

    /** Generates (if absent) and persists the customer's referral code. */
    @Transactional
    public String generateAndSaveReferralCode(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException("Customer not found"));
        if (!StringUtils.hasText(customer.getReferralCode())) {
            customer.setReferralCode(generateCode(customer));
            customerRepository.save(customer);
        }
        return customer.getReferralCode();
    }

    private void applyReferral(Customer newCustomer, String referralCode) {
        Customer referrer = customerRepository.findByReferralCode(referralCode)
                .orElseThrow(() -> new BusinessException("Invalid referral code"));
        if (referrer.getId().equals(newCustomer.getId())) {
            throw new BusinessException("Cannot use your own referral code");
        }
        if (newCustomer.getReferredById() != null) {
            // ADR-005 idempotent apply: an already-referred customer is never re-bound.
            log.info("Referral apply ignored: customer {} already referred by {}",
                    newCustomer.getId(), newCustomer.getReferredById());
            return;
        }
        newCustomer.setReferredById(referrer.getId());
        // Binding + reward accounting is owned by the referral module
        // (its reward ledger credits the referrer exactly once); identity
        // only records the display binding and keeps the referee welcome
        // bonus flow it already had.
        boolean accepted = referralServiceClient.applyReferral(
                newCustomer.getId(), newCustomer.getEmail(), referralCode);
        if (accepted && referralProperties.getRefereeBonusAmount() > 0) {
            walletCreditPort.credit(
                    newCustomer.getId(),
                    referralProperties.getRefereeBonusAmount(),
                    "REFERRAL_BONUS",
                    null,
                    "Welcome bonus via referral code");
        }
    }

    /**
     * Code generation via the referral module (single generator, ADR-005).
     * Fallback: a collision-safe random code (unbounded alphabet tail, never
     * id-modulo) so registration is never blocked by a referral outage.
     */
    private String generateCode(Customer customer) {
        String delegated = referralServiceClient.generateCode(customer.getId());
        if (StringUtils.hasText(delegated)) {
            return delegated;
        }
        log.warn("Referral service unavailable; issuing local display code customerId={}", customer.getId());
        return "BK" + customer.getId()
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                        .toUpperCase(Locale.ROOT);
    }
}

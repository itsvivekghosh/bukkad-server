package com.bhukkad.identity.referral;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.ratelimit.RateLimitDecision;
import com.bhukkad.common.ratelimit.RateLimitExceededException;
import com.bhukkad.common.ratelimit.RateLimitService;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;

/**
 * Slim port of the monolith {@code com.bhukkad.referral.ReferralService} for
 * the identity service. Owns referral-code lifecycle on the identity-owned
 * customer aggregate. Wallet crediting (the monolith referrer/referee bonus)
 * stays in the monolith working copy under {@code com.bhukkad.referral} —
 * that flow crosses the wallet domain boundary which is not yet extracted.
 *
 * <p>TODO Batch (post-wallet extraction): re-wire wallet crediting through an
 * outbox event or a {@code wallet} HTTP client instead of the direct
 * {@code com.bhukkad.wallet.WalletService} call the monolith uses.</p>
 */
@Service
@RequiredArgsConstructor
public class ReferralService {

    private final CustomerRepository customerRepository;
    private final RateLimitService rateLimitService;

    @Value("${bhukkad.referral.enabled:true}")
    private boolean referralEnabled;

    @Value("${bhukkad.referral.bonus-amount:50.0}")
    private double bonusAmount;

    @Value("${bhukkad.referral.referee-bonus-amount:25.0}")
    private double refereeBonusAmount;

    @Transactional
    public void initializeNewCustomer(Customer customer, String referralCodeInput) {
        customer.setReferralCode(generateUniqueCode(customer));
        if (referralEnabled && StringUtils.hasText(referralCodeInput)) {
            applyReferral(customer, referralCodeInput.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Transactional(readOnly = true)
    public int getReferralsCount(Long customerId) {
        return (int) customerRepository.countByReferredById(customerId);
    }

    @Transactional(readOnly = true)
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

    @Transactional
    public String generateAndSaveReferralCode(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException("Customer not found"));
        if (!StringUtils.hasText(customer.getReferralCode())) {
            customer.setReferralCode(generateUniqueCode(customer));
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
        newCustomer.setReferredById(referrer.getId());
        // Wallet crediting intentionally omitted in this slim port. See TODO
        // in the class Javadoc; the monolith working copy under
        // com.bhukkad.referral.ReferralService still performs both bonus
        // credits until the wallet domain is extracted.
    }

    private String generateUniqueCode(Customer customer) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "BK" + customer.getId()
                    + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
            if (customerRepository.findByReferralCode(code).isEmpty()) {
                return code;
            }
        }
        return "BK" + customer.getId() + System.currentTimeMillis() % 10000;
    }
}
package com.bhukkad.referral;

import com.bhukkad.config.ReferralProperties;
import com.bhukkad.ratelimit.RateLimitDecision;
import com.bhukkad.ratelimit.RateLimitService;
import com.bhukkad.dto.response.ReferralInfoResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.WalletTransactionRepository;
import com.bhukkad.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReferralService {

    private final CustomerRepository customerRepository;
    private final WalletService walletService;
    private final WalletTransactionRepository walletTransactionRepository;
    private final ReferralProperties referralProperties;
    private final RateLimitService rateLimitService;

    /**
     * Assigns a referral code to a new customer and, when enabled, links the
     * referrer relationship.
     *
     * <p>Deliberately does <em>not</em> save the customer: the caller owns the
     * persistence (registration already saved the customer and keeps the entity
     * managed inside its transaction, so these mutations are flushed with the
     * same commit). This avoids a redundant second INSERT/UPDATE round-trip.</p>
     */
    @Transactional
    public void initializeNewCustomer(Customer customer, String referralCodeInput) {
        customer.setReferralCode(generateUniqueCode(customer));
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
        double bonusEarned = walletTransactionRepository.sumReferralCredits(
                customerId, WalletTransaction.TransactionType.REFERRAL_BONUS);
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
        RateLimitDecision decision = rateLimitService.check("referral", key);
        if (!decision.allowed()) {
            throw new com.bhukkad.exception.RateLimitExceededException(
                    "Too many requests. Try again later.", decision.retryAfterSeconds());
        }
    }

    /** Generates (if absent) and persists the customer's referral code. */
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
        newCustomer.setReferredBy(referrer);
        if (referralProperties.getBonusAmount() > 0) {
            walletService.credit(
                    referrer,
                    referralProperties.getBonusAmount(),
                    WalletTransaction.TransactionType.REFERRAL_BONUS,
                    null,
                    "Referral bonus for inviting " + newCustomer.getEmail());
        }
        if (referralProperties.getRefereeBonusAmount() > 0) {
            walletService.credit(
                    newCustomer,
                    referralProperties.getRefereeBonusAmount(),
                    WalletTransaction.TransactionType.REFERRAL_BONUS,
                    null,
                    "Welcome bonus via referral code");
        }
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

package com.bhukkad.referral;

import com.bhukkad.config.ReferralProperties;
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

    @Transactional
    public void initializeNewCustomer(Customer customer, String referralCodeInput) {
        customer.setReferralCode(generateUniqueCode(customer));
        if (referralProperties.isEnabled() && StringUtils.hasText(referralCodeInput)) {
            applyReferral(customer, referralCodeInput.trim().toUpperCase(Locale.ROOT));
        }
        customerRepository.save(customer);
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

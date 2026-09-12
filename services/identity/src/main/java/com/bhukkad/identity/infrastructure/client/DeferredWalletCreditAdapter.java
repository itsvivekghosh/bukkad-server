package com.bhukkad.identity.infrastructure.client;

import com.bhukkad.identity.api.WalletCreditPort;

import com.bhukkad.identity.api.WalletCreditPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Deferred in-process implementation of {@link WalletCreditPort}. The wallet
 * domain (authoritative {@code wallet_balances} ledger) is not yet extracted,
 * so crediting is a no-op that logs; the monolith working copy under
 * {@code com.bhukkad.referral.ReferralService} still performs the real bonus
 * credits during the transition.
 *
 * <p>TODO (wallet extraction wave): replace with an outbox event or a
 * {@code wallet} HTTP client so identity-initiated referrals credit the real
 * ledger.</p>
 */
@Slf4j
@Component
public class DeferredWalletCreditAdapter implements WalletCreditPort {

    @Override
    public void credit(Long customerId, double amount, String type, Long paymentId, String description) {
        log.info("WALLET_CREDIT_DEFERRED | customerId={} | amount={} | type={} | description={}",
                customerId, amount, type, description);
    }

    @Override
    public double referralBonusEarned(Long customerId) {
        // Wallet ledger is not owned by identity yet; return 0 until the
        // wallet port is backed by the real ledger (see class TODO).
        return 0.0;
    }
}

package com.bhukkad.identity.api;

/**
 * Narrow write port from the identity domain into the wallet domain
 * (WAVE 2): referral bonus crediting and referral-bonus balance lookup.
 *
 * <p>The identity service must not import the monolith
 * {@code com.bhukkad.wallet.WalletService} or {@code WalletTransaction}
 * entity, so those dependencies are expressed through this port. The wallet
 * transaction type is carried as a string (documented values, e.g.
 * {@code REFERRAL_BONUS}) to keep the wallet enum out of the identity
 * contract.</p>
 *
 * <p>Implemented in-process by {@code DeferredWalletCreditAdapter} until the
 * wallet domain is physically extracted, after which this becomes an
 * HTTP/gRPC call — same trajectory as {@link CustomerWalletSyncPort}.</p>
 */
public interface WalletCreditPort {

    /**
     * Credits {@code amount} to the customer's wallet.
     *
     * @param customerId identity-owned customer id
     * @param amount     positive credit amount
     * @param type       wallet transaction type, e.g. {@code REFERRAL_BONUS}
     * @param paymentId  related payment id or {@code null}
     * @param description human-readable ledger description
     */
    void credit(Long customerId, double amount, String type, Long paymentId, String description);

    /**
     * @param customerId identity-owned customer id
     * @return total amount credited to the customer for the given referral
     *         bonus type (0 when no credits exist)
     */
    double referralBonusEarned(Long customerId);
}

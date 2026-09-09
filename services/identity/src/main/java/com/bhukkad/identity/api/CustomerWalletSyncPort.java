package com.bhukkad.identity.api;

/**
 * Write access from the wallet domain to the identity domain's synchronised
 * {@code customers.wallet_balance} read-model. The wallet owns the
 * authoritative {@code wallet_balances} table; this port keeps the legacy
 * column in step inside the same transaction during the transition.
 *
 * <p>Implemented by the identity domain ({@code CustomerWalletSyncAdapter}).
 * When identity is physically extracted this becomes an HTTP/gRPC call.</p>
 */
public interface CustomerWalletSyncPort {

    /**
     * @param customerId identity-owned customer id
     * @param newBalance the wallet domain's authoritative new balance
     * @throws com.bhukkad.common.error.ResourceNotFoundException when missing
     */
    void syncWalletBalance(Long customerId, double newBalance);
}

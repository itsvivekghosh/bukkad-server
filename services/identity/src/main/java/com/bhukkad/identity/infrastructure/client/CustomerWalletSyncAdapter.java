package com.bhukkad.identity.infrastructure.client;

import com.bhukkad.identity.api.CustomerWalletSyncPort;
import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.repository.CustomerRepository;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.identity.api.CustomerWalletSyncPort;
import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Identity-side adapter for the wallet domain's balance synchronisation.
 * Mandatory propagation: the wallet service calls this inside its own
 * transaction (the balance lock and the sync must commit together).
 */
@Service
@RequiredArgsConstructor
public class CustomerWalletSyncAdapter implements CustomerWalletSyncPort {

    private final CustomerRepository customerRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void syncWalletBalance(Long customerId, BigDecimal newBalance) {
        if (newBalance == null || newBalance.signum() < 0) {
            throw new IllegalArgumentException(
                    "Wallet balance must be a non-negative amount: " + newBalance);
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        customer.setWalletBalance(newBalance.setScale(2, java.math.RoundingMode.HALF_UP));
    }
}

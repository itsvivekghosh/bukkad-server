package com.bhukkad.identity;

import com.bhukkad.entity.Customer;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.identity.api.CustomerWalletSyncPort;
import com.bhukkad.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    public void syncWalletBalance(Long customerId, double newBalance) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        customer.setWalletBalance(newBalance);
    }
}

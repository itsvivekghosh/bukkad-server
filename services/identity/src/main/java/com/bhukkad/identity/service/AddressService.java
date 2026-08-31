package com.bhukkad.identity.service;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.identity.domain.Address;
import com.bhukkad.identity.domain.AddressRepository;
import com.bhukkad.identity.domain.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Customer address management.
 */
@Service
@RequiredArgsConstructor
public class AddressService {

    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final IdentityEventPublisher eventPublisher;

    public record AddressInput(String label, String line1, String city, String state, String zipCode, boolean isDefault) {
    }

    @Transactional
    public Address addAddress(Long customerId, AddressInput input) {
        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found: " + customerId);
        }
        if (input.isDefault()) {
            // Clear existing default flags for this customer
            addressRepository.findByCustomerId(customerId)
                    .forEach(a -> { a.setIsDefault(false); });
        }
        Address address = new Address();
        address.setCustomerId(customerId);
        address.setLabel(input.label());
        address.setLine1(input.line1());
        address.setCity(input.city());
        address.setState(input.state());
        address.setZipCode(input.zipCode());
        address.setIsDefault(input.isDefault());
        address = addressRepository.save(address);

        eventPublisher.addressChanged(customerId, address.getId());
        return address;
    }

    @Transactional(readOnly = true)
    public List<Address> listAddresses(Long customerId) {
        return addressRepository.findByCustomerId(customerId);
    }
}
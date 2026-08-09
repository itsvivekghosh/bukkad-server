package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Customer;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final SecurityUtils securityUtils;

    @Override
    public Customer getCustomerById(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    @Override
    public Customer getCurrentCustomer() {
        Long userId = securityUtils.getCurrentUserId();
        return customerRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    @Override
    @Transactional
    public Customer updateProfile(Long id, Customer customer) {
        if (!securityUtils.isCurrentUser(id)) {
            throw new UnauthorizedException("Cannot update another user's profile");
        }

        Customer existingCustomer = getCustomerById(id);
        existingCustomer.setFullName(customer.getFullName());
        existingCustomer.setPhoneNumber(customer.getPhoneNumber());
        existingCustomer.setProfileImageUrl(customer.getProfileImageUrl());

        return customerRepository.save(existingCustomer);
    }

    @Override
    @Transactional
    public void deleteAccount(Long id) {
        if (!securityUtils.isCurrentUser(id)) {
            throw new UnauthorizedException("Cannot delete another user's account");
        }

        Customer customer = getCustomerById(id);
        customer.setActive(false);
        customerRepository.save(customer);
    }

    @Override
    @Transactional
    public AddressResponse addAddress(AddressRequest request) {
        Customer customer = getCurrentCustomer();

        Address address = new Address();
        address.setCustomer(customer);
        address.setAddressLine1(request.getAddressLine1());
        address.setAddressLine2(request.getAddressLine2());
        address.setCity(request.getCity());
        address.setState(request.getState());
        address.setPincode(request.getPincode());
        address.setLandmark(request.getLandmark());
        address.setType(request.getType());
        address.setLabel(request.getLabel());
        address.setLatitude(request.getLatitude());
        address.setLongitude(request.getLongitude());
        address.setIsDefault(request.getIsDefault());

        // If this is the first address or set as default
        if (customer.getAddresses().isEmpty() || Boolean.TRUE.equals(request.getIsDefault())) {
            // Set all other addresses as non-default
            customer.getAddresses().forEach(addr -> addr.setIsDefault(false));
            address.setIsDefault(true);
        }

        address = addressRepository.save(address);
        return mapToAddressResponse(address);
    }

    @Override
    public List<AddressResponse> getAddresses() {
        Customer customer = getCurrentCustomer();
        return customer.getAddresses().stream()
                .map(this::mapToAddressResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AddressResponse updateAddress(Long addressId, AddressRequest request) {
        Customer customer = getCurrentCustomer();

        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!address.getCustomer().getId().equals(customer.getId())) {
            throw new UnauthorizedException("Cannot update another user's address");
        }

        address.setAddressLine1(request.getAddressLine1());
        address.setAddressLine2(request.getAddressLine2());
        address.setCity(request.getCity());
        address.setState(request.getState());
        address.setPincode(request.getPincode());
        address.setLandmark(request.getLandmark());
        address.setType(request.getType());
        address.setLabel(request.getLabel());
        address.setLatitude(request.getLatitude());
        address.setLongitude(request.getLongitude());

        address = addressRepository.save(address);
        return mapToAddressResponse(address);
    }

    @Override
    @Transactional
    public void deleteAddress(Long addressId) {
        Customer customer = getCurrentCustomer();

        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!address.getCustomer().getId().equals(customer.getId())) {
            throw new UnauthorizedException("Cannot delete another user's address");
        }

        addressRepository.delete(address);
    }

    @Override
    @Transactional
    public AddressResponse setDefaultAddress(Long addressId) {
        Customer customer = getCurrentCustomer();

        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!address.getCustomer().getId().equals(customer.getId())) {
            throw new UnauthorizedException("Cannot update another user's address");
        }

        // Set all addresses as non-default
        customer.getAddresses().forEach(addr -> addr.setIsDefault(false));

        // Set this address as default
        address.setIsDefault(true);
        address = addressRepository.save(address);

        return mapToAddressResponse(address);
    }

    @Override
    public Double getWalletBalance() {
        Customer customer = getCurrentCustomer();
        return customer.getWalletBalance();
    }

    @Override
    @Transactional
    public void addMoneyToWallet(Double amount) {
        Customer customer = getCurrentCustomer();
        customer.setWalletBalance(customer.getWalletBalance() + amount);
        customerRepository.save(customer);
    }

    @Override
    public Integer getLoyaltyPoints() {
        Customer customer = getCurrentCustomer();
        return customer.getLoyaltyPoints();
    }

    private AddressResponse mapToAddressResponse(Address address) {
        return AddressResponse.builder()
                .id(address.getId())
                .addressLine1(address.getAddressLine1())
                .addressLine2(address.getAddressLine2())
                .city(address.getCity())
                .state(address.getState())
                .pincode(address.getPincode())
                .landmark(address.getLandmark())
                .type(address.getType() != null ? address.getType().name() : null)
                .label(address.getLabel())
                .latitude(address.getLatitude())
                .longitude(address.getLongitude())
                .isDefault(address.getIsDefault())
                .build();
    }
}
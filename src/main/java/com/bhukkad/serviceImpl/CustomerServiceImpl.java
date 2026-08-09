package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.CustomerProfileResponse;
import com.bhukkad.dto.response.CustomerResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceImpl.class);

    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final OrderRepository orderRepository;
    private final SecurityUtils securityUtils;

    @Override
    @Transactional(readOnly = true)
    public CustomerProfileResponse getProfile() {
        Customer customer = getCurrentCustomer();

        List<AddressResponse> addresses = addressRepository
                .findByCustomerId(customer.getId())
                .stream()
                .map(this::mapToAddressResponse)
                .collect(Collectors.toList());

        long totalOrders = orderRepository.countByCustomerId(customer.getId());

        return CustomerProfileResponse.builder()
                .id(customer.getId())
                .email(customer.getEmail())
                .fullName(customer.getFullName())
                .phoneNumber(customer.getPhoneNumber())
                .profileImageUrl(customer.getProfileImageUrl())
                .active(customer.getActive())
                .emailVerified(customer.getEmailVerified())
                .loyaltyPoints(customer.getLoyaltyPoints())
                .walletBalance(customer.getWalletBalance())
                .role(customer.getRole().name())
                .createdAt(customer.getCreatedAt() != null ? customer.getCreatedAt().toString() : null)
                .addresses(addresses)
                .totalOrders((int) totalOrders)
                .build();
    }

    @Override
    @Transactional
    public CustomerResponse updateProfile(String fullName, String phoneNumber, String profileImageUrl) {
        Customer customer = getCurrentCustomer();

        if (fullName != null && !fullName.isEmpty()) customer.setFullName(fullName);
        if (phoneNumber != null && !phoneNumber.isEmpty()) customer.setPhoneNumber(phoneNumber);
        if (profileImageUrl != null && !profileImageUrl.isEmpty()) customer.setProfileImageUrl(profileImageUrl);

        customer = customerRepository.save(customer);
        log.info("Profile updated for customer: {}", customer.getId());
        return mapToCustomerResponse(customer);
    }

    @Override
    @Transactional
    public void deleteAccount() {
        // Gets current logged-in customer - no need for ID parameter
        Customer customer = getCurrentCustomer();
        customer.setActive(false);
        customerRepository.save(customer);
        log.info("Account deactivated | CustomerId: {}", customer.getId());
    }

    @Override
    @Transactional
    public AddressResponse addAddress(AddressRequest request) {
        Customer customer = getCurrentCustomer();

        Address address = new Address();
        address.setCustomer(customer);
        mapRequestToAddress(request, address);

        List<Address> existingAddresses = addressRepository.findByCustomerId(customer.getId());

        if (existingAddresses.isEmpty() || Boolean.TRUE.equals(request.getIsDefault())) {
            existingAddresses.forEach(addr -> {
                addr.setIsDefault(false);
                addressRepository.save(addr);
            });
            address.setIsDefault(true);
        }

        address = addressRepository.save(address);
        return mapToAddressResponse(address);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AddressResponse> getAddresses() {
        Customer customer = getCurrentCustomer();
        return addressRepository.findByCustomerId(customer.getId())
                .stream()
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
            throw new UnauthorizedException("Not your address");
        }

        mapRequestToAddress(request, address);
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
            throw new UnauthorizedException("Not your address");
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
            throw new UnauthorizedException("Not your address");
        }

        addressRepository.findByCustomerId(customer.getId())
                .forEach(addr -> {
                    addr.setIsDefault(false);
                    addressRepository.save(addr);
                });

        address.setIsDefault(true);
        address = addressRepository.save(address);
        return mapToAddressResponse(address);
    }

    @Override
    @Transactional(readOnly = true)
    public Double getWalletBalance() {
        return getCurrentCustomer().getWalletBalance();
    }

    @Override
    @Transactional
    public void addMoneyToWallet(Double amount) {
        if (amount == null || amount <= 0) {
            throw new BusinessException("Amount must be positive");
        }
        Customer customer = getCurrentCustomer();
        customer.setWalletBalance(customer.getWalletBalance() + amount);
        customerRepository.save(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getLoyaltyPoints() {
        return getCurrentCustomer().getLoyaltyPoints();
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerProfileResponse getCustomerById(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + customerId));
        return buildProfileResponse(customer);
    }

    public Customer getCurrentCustomer() {
        User user = securityUtils.getCurrentUser();

        if (user.getRole() != User.UserRole.CUSTOMER) {
            throw new UnauthorizedException("Not a customer account");
        }

        return customerRepository.findById(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    private void mapRequestToAddress(AddressRequest request, Address address) {
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
        if (request.getIsDefault() != null) {
            address.setIsDefault(request.getIsDefault());
        }
    }

    private CustomerResponse mapToCustomerResponse(Customer customer) {
        return CustomerResponse.builder()
                .id(customer.getId())
                .email(customer.getEmail())
                .fullName(customer.getFullName())
                .phoneNumber(customer.getPhoneNumber())
                .profileImageUrl(customer.getProfileImageUrl())
                .active(customer.getActive())
                .emailVerified(customer.getEmailVerified())
                .loyaltyPoints(customer.getLoyaltyPoints())
                .walletBalance(customer.getWalletBalance())
                .role(customer.getRole().name())
                .createdAt(customer.getCreatedAt() != null ? customer.getCreatedAt().toString() : null)
                .build();
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

    private CustomerProfileResponse buildProfileResponse(Customer customer) {
        List<AddressResponse> addresses = addressRepository
                .findByCustomerId(customer.getId())
                .stream()
                .map(this::mapToAddressResponse)
                .collect(Collectors.toList());

        long totalOrders = orderRepository.countByCustomerId(customer.getId());

        return CustomerProfileResponse.builder()
                .id(customer.getId())
                .email(customer.getEmail())
                .fullName(customer.getFullName())
                .phoneNumber(customer.getPhoneNumber())
                .profileImageUrl(customer.getProfileImageUrl())
                .active(customer.getActive())
                .emailVerified(customer.getEmailVerified())
                .loyaltyPoints(customer.getLoyaltyPoints())
                .walletBalance(customer.getWalletBalance())
                .role(customer.getRole().name())
                .createdAt(customer.getCreatedAt() != null ? customer.getCreatedAt().toString() : null)
                .addresses(addresses)
                .totalOrders((int) totalOrders)
                .build();
    }
}
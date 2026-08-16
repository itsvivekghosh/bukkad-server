package com.bhukkad.serviceImpl;

import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.CustomerOrderStatsResponse;
import com.bhukkad.dto.response.CustomerProfileResponse;
import com.bhukkad.dto.response.CustomerResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.config.WalletProperties;
import com.bhukkad.entity.WalletTransaction;
import org.springframework.dao.DataAccessException;
import com.bhukkad.wallet.WalletService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final OrderRepository orderRepository;
    private final SecurityUtils securityUtils;
    private final WalletProperties walletProperties;
    private final WalletService walletService;

    @Override
    @UseReadReplica
    public CustomerProfileResponse getProfile() {
        return buildProfileResponse(getCurrentCustomer());
    }

    @Override
    @Transactional
    public CustomerResponse updateProfile(String fullName, String phoneNumber, String profileImageUrl) {
        Customer customer = getCurrentCustomer();

        if (fullName != null && !fullName.isEmpty()) {
            customer.setFullName(fullName);
        }
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            customer.setPhoneNumber(phoneNumber);
        }
        if (profileImageUrl != null && !profileImageUrl.isEmpty()) {
            customer.setProfileImageUrl(profileImageUrl);
        }

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
    @UseReadReplica
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

        Address address = addressRepository.findByIdWithCustomer(addressId)
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

        Address address = addressRepository.findByIdWithCustomer(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!address.getCustomer().getId().equals(customer.getId())) {
            throw new UnauthorizedException("Not your address");
        }

        long orderCount = orderRepository.countByDeliveryAddressId(addressId);
        if (orderCount > 0) {
            throw new BusinessException("Cannot delete address because it is used in existing orders");
        }

        try {
            addressRepository.delete(address);
        } catch (DataAccessException e) {
            throw new BusinessException("Cannot delete address due to dependencies: " + e.getMostSpecificCause().getMessage());
        }
    }

    @Override
    @Transactional
    public AddressResponse setDefaultAddress(Long addressId) {
        Customer customer = getCurrentCustomer();

        Address address = addressRepository.findByIdWithCustomer(addressId)
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
    @UseReadReplica
    public Double getWalletBalance() {
        return getCurrentCustomer().getWalletBalance();
    }

    @Override
    @Transactional
    public void addMoneyToWallet(Double amount) {
        if (amount == null || amount <= 0) {
            throw new BusinessException("Amount must be positive");
        }
        if (!walletProperties.isAllowDirectTopUp()) {
            throw new BusinessException(
                    "Direct wallet credit is disabled. Use POST /customers/wallet/top-up to pay via gateway.");
        }
        Customer customer = getCurrentCustomer();
        walletService.credit(
                customer,
                amount,
                WalletTransaction.TransactionType.ADJUSTMENT,
                null,
                "Direct wallet top-up (dev/admin)");
        log.info("Direct wallet credit | customerId={} | amount={}", customer.getId(), amount);
    }

    @Override
    @UseReadReplica
    public Integer getLoyaltyPoints() {
        return getCurrentCustomer().getLoyaltyPoints();
    }

    @Override
    @UseReadReplica
    public CustomerOrderStatsResponse getOrderStats() {
        Customer customer = getCurrentCustomer();
        long customerId = customer.getId();
        long total = orderRepository.countByCustomerId(customerId);
        long delivered = orderRepository.countByCustomerIdAndStatus(customerId, Order.OrderStatus.DELIVERED);
        long cancelled = orderRepository.countByCustomerIdAndStatus(customerId, Order.OrderStatus.CANCELLED);
        Double spent = orderRepository.sumDeliveredSpendByCustomerId(customerId);
        return CustomerOrderStatsResponse.builder()
                .totalOrders(total)
                .deliveredOrders(delivered)
                .cancelledOrders(cancelled)
                .totalSpent(spent != null ? spent : 0.0)
                .loyaltyPoints(customer.getLoyaltyPoints())
                .build();
    }

    @Override
    @UseReadReplica
    public CustomerProfileResponse getCustomerById(Long customerId) {
        if (!customerId.equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Cannot access another customer's profile");
        }
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
                .addresses(addressRepository
                        .findByCustomerId(customer.getId())
                        .stream()
                        .map(this::mapToAddressResponse)
                        .collect(Collectors.toList()))
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
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
import com.bhukkad.config.WalletProperties;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.wallet.WalletService;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private WalletProperties walletProperties;
    @Mock
    private WalletService walletService;

    @InjectMocks
    private CustomerServiceImpl customerService;

    private Customer customer() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setEmail("cust@example.com");
        customer.setFullName("Jane Doe");
        customer.setPhoneNumber("9876543210");
        customer.setProfileImageUrl("img.png");
        customer.setActive(true);
        customer.setEmailVerified(true);
        customer.setLoyaltyPoints(50);
        customer.setWalletBalance(100.0);
        customer.setRole(User.UserRole.CUSTOMER);
        customer.setCreatedAt(LocalDateTime.of(2024, 1, 15, 10, 30));
        return customer;
    }

    private void stubCurrentCustomer(Customer customer) {
        User user = new User();
        user.setId(customer.getId());
        user.setRole(User.UserRole.CUSTOMER);
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
    }

    private AddressRequest addressRequest(Boolean isDefault) {
        AddressRequest request = new AddressRequest();
        request.setAddressLine1("Line 1");
        request.setAddressLine2("Line 2");
        request.setCity("Pune");
        request.setState("MH");
        request.setPincode("411001");
        request.setLandmark("Near park");
        request.setType(Address.AddressType.HOME);
        request.setLabel("Home");
        request.setLatitude(18.5);
        request.setLongitude(73.8);
        request.setIsDefault(isDefault);
        return request;
    }

    private Address address(Long id, Customer owner, boolean isDefault) {
        Address address = new Address();
        address.setId(id);
        address.setCustomer(owner);
        address.setAddressLine1("Line 1");
        address.setCity("Pune");
        address.setState("MH");
        address.setPincode("411001");
        address.setType(Address.AddressType.HOME);
        address.setLatitude(18.5);
        address.setLongitude(73.8);
        address.setIsDefault(isDefault);
        return address;
    }

    @Test
    void getCurrentCustomer_nonCustomerRole_throwsUnauthorized() {
        User user = new User();
        user.setId(1L);
        user.setRole(User.UserRole.ADMIN);
        when(securityUtils.getCurrentUser()).thenReturn(user);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class, () -> customerService.getCurrentCustomer());
        assertEquals("Not a customer account", ex.getMessage());
    }

    @Test
    void getCurrentCustomer_restaurantOwner_throwsUnauthorized() {
        User user = new User();
        user.setId(2L);
        user.setRole(User.UserRole.RESTAURANT_OWNER);
        when(securityUtils.getCurrentUser()).thenReturn(user);

        assertThrows(UnauthorizedException.class, () -> customerService.getCurrentCustomer());
    }

    @Test
    void getCurrentCustomer_missing_throwsResourceNotFound() {
        User user = new User();
        user.setId(1L);
        user.setRole(User.UserRole.CUSTOMER);
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> customerService.getCurrentCustomer());
        assertEquals("Customer not found", ex.getMessage());
    }

    @Test
    void getCurrentCustomer_success() {
        Customer customer = customer();
        stubCurrentCustomer(customer);

        assertEquals(1L, customerService.getCurrentCustomer().getId());
    }

    @Test
    void getProfile_withCreatedAtAndAddresses() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Address addr = address(3L, customer, true);
        when(addressRepository.findByCustomerId(1L)).thenReturn(List.of(addr));
        when(orderRepository.countByCustomerId(1L)).thenReturn(4L);

        CustomerProfileResponse profile = customerService.getProfile();

        assertEquals(1L, profile.getId());
        assertEquals("cust@example.com", profile.getEmail());
        assertEquals("Jane Doe", profile.getFullName());
        assertEquals("CUSTOMER", profile.getRole());
        assertEquals(customer.getCreatedAt().toString(), profile.getCreatedAt());
        assertEquals(4, profile.getTotalOrders());
        assertEquals(1, profile.getAddresses().size());
        assertEquals("HOME", profile.getAddresses().get(0).getType());
        assertEquals(50, profile.getLoyaltyPoints());
        assertEquals(100.0, profile.getWalletBalance());
    }

    @Test
    void getProfile_nullCreatedAt_andNullAddressType() {
        Customer customer = customer();
        customer.setCreatedAt(null);
        stubCurrentCustomer(customer);
        Address addr = address(3L, customer, false);
        addr.setType(null);
        when(addressRepository.findByCustomerId(1L)).thenReturn(List.of(addr));
        when(orderRepository.countByCustomerId(1L)).thenReturn(0L);

        CustomerProfileResponse profile = customerService.getProfile();

        assertNull(profile.getCreatedAt());
        assertNull(profile.getAddresses().get(0).getType());
        assertEquals(0, profile.getTotalOrders());
    }

    @Test
    void updateProfile_allFieldsUpdated() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        when(customerRepository.save(customer)).thenReturn(customer);

        CustomerResponse response = customerService.updateProfile("New Name", "1111111111", "new.png");

        assertEquals("New Name", customer.getFullName());
        assertEquals("1111111111", customer.getPhoneNumber());
        assertEquals("new.png", customer.getProfileImageUrl());
        assertEquals("New Name", response.getFullName());
        assertEquals(customer.getCreatedAt().toString(), response.getCreatedAt());
    }

    @Test
    void updateProfile_nullAndEmptyFields_areIgnored() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        when(customerRepository.save(customer)).thenReturn(customer);

        customerService.updateProfile(null, "", null);
        assertEquals("Jane Doe", customer.getFullName());
        assertEquals("9876543210", customer.getPhoneNumber());
        assertEquals("img.png", customer.getProfileImageUrl());

        customerService.updateProfile("", null, "");
        assertEquals("Jane Doe", customer.getFullName());
        assertEquals("9876543210", customer.getPhoneNumber());
        assertEquals("img.png", customer.getProfileImageUrl());
    }

    @Test
    void updateProfile_nullCreatedAt_mapsToNull() {
        Customer customer = customer();
        customer.setCreatedAt(null);
        stubCurrentCustomer(customer);
        when(customerRepository.save(customer)).thenReturn(customer);

        CustomerResponse response = customerService.updateProfile("Only Name", null, "");

        assertEquals("Only Name", response.getFullName());
        assertNull(response.getCreatedAt());
        assertEquals("9876543210", customer.getPhoneNumber());
        assertEquals("img.png", customer.getProfileImageUrl());
    }

    @Test
    void deleteAccount_deactivatesCustomer() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        when(customerRepository.save(customer)).thenReturn(customer);

        customerService.deleteAccount();

        assertFalse(customer.getActive());
        verify(customerRepository).save(customer);
    }

    @Test
    void addAddress_firstAddress_isForcedDefault() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        when(addressRepository.findByCustomerId(1L)).thenReturn(new ArrayList<>());
        when(addressRepository.save(any(Address.class))).thenAnswer(inv -> {
            Address a = inv.getArgument(0);
            a.setId(7L);
            return a;
        });

        AddressResponse response = customerService.addAddress(addressRequest(false));

        assertTrue(response.getIsDefault());
        assertEquals(7L, response.getId());
        assertEquals("Pune", response.getCity());
    }

    @Test
    void addAddress_isDefaultTrue_clearsExistingDefaults() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Address existing = address(2L, customer, true);
        List<Address> existingList = new ArrayList<>();
        existingList.add(existing);
        when(addressRepository.findByCustomerId(1L)).thenReturn(existingList);
        when(addressRepository.save(any(Address.class))).thenAnswer(inv -> {
            Address a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(8L);
            }
            return a;
        });

        AddressResponse response = customerService.addAddress(addressRequest(true));

        assertFalse(existing.getIsDefault());
        assertTrue(response.getIsDefault());
    }

    @Test
    void addAddress_nonDefaultWithExisting_doesNotClearDefaults() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Address existing = address(2L, customer, true);
        when(addressRepository.findByCustomerId(1L)).thenReturn(List.of(existing));
        when(addressRepository.save(any(Address.class))).thenAnswer(inv -> {
            Address a = inv.getArgument(0);
            a.setId(9L);
            return a;
        });

        AddressResponse response = customerService.addAddress(addressRequest(false));

        assertTrue(existing.getIsDefault());
        assertFalse(response.getIsDefault());
    }

    @Test
    void addAddress_nullIsDefaultWithExisting_doesNotForceDefault() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Address existing = address(2L, customer, true);
        when(addressRepository.findByCustomerId(1L)).thenReturn(List.of(existing));
        when(addressRepository.save(any(Address.class))).thenAnswer(inv -> {
            Address a = inv.getArgument(0);
            a.setId(11L);
            return a;
        });

        AddressResponse response = customerService.addAddress(addressRequest(null));

        assertTrue(existing.getIsDefault());
        assertFalse(Boolean.TRUE.equals(response.getIsDefault()));
    }

    @Test
    void getAddresses_returnsMappedList() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        when(addressRepository.findByCustomerId(1L)).thenReturn(List.of(address(3L, customer, true)));

        List<AddressResponse> addresses = customerService.getAddresses();

        assertEquals(1, addresses.size());
        assertEquals(3L, addresses.get(0).getId());
        assertEquals("HOME", addresses.get(0).getType());
    }

    @Test
    void getAddresses_empty() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        when(addressRepository.findByCustomerId(1L)).thenReturn(Collections.emptyList());

        assertTrue(customerService.getAddresses().isEmpty());
    }

    @Test
    void updateAddress_notFound() {
        stubCurrentCustomer(customer());
        when(addressRepository.findByIdWithCustomer(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> customerService.updateAddress(99L, addressRequest(false)));
        assertEquals("Address not found", ex.getMessage());
    }

    @Test
    void updateAddress_notOwned_throwsUnauthorized() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Customer other = customer();
        other.setId(99L);
        when(addressRepository.findByIdWithCustomer(3L)).thenReturn(Optional.of(address(3L, other, false)));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> customerService.updateAddress(3L, addressRequest(false)));
        assertEquals("Not your address", ex.getMessage());
    }

    @Test
    void updateAddress_success_includingNullIsDefault() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Address existing = address(3L, customer, false);
        when(addressRepository.findByIdWithCustomer(3L)).thenReturn(Optional.of(existing));
        when(addressRepository.save(existing)).thenReturn(existing);

        AddressRequest request = addressRequest(null);
        request.setCity("Mumbai");
        AddressResponse response = customerService.updateAddress(3L, request);

        assertEquals("Mumbai", response.getCity());
        assertFalse(existing.getIsDefault());
    }

    @Test
    void updateAddress_setsIsDefaultWhenProvided() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Address existing = address(3L, customer, false);
        when(addressRepository.findByIdWithCustomer(3L)).thenReturn(Optional.of(existing));
        when(addressRepository.save(existing)).thenReturn(existing);

        AddressResponse response = customerService.updateAddress(3L, addressRequest(true));

        assertTrue(response.getIsDefault());
    }

    @Test
    void deleteAddress_notFound() {
        stubCurrentCustomer(customer());
        when(addressRepository.findByIdWithCustomer(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> customerService.deleteAddress(99L));
        assertEquals("Address not found", ex.getMessage());
    }

    @Test
    void deleteAddress_notOwned_throwsUnauthorized() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Customer other = customer();
        other.setId(88L);
        when(addressRepository.findByIdWithCustomer(3L)).thenReturn(Optional.of(address(3L, other, false)));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> customerService.deleteAddress(3L));
        assertEquals("Not your address", ex.getMessage());
        verify(addressRepository, never()).delete(any());
    }

    @Test
    void deleteAddress_success() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Address existing = address(3L, customer, false);
        when(addressRepository.findByIdWithCustomer(3L)).thenReturn(Optional.of(existing));

        customerService.deleteAddress(3L);

        verify(addressRepository).delete(existing);
    }

    @Test
    void setDefaultAddress_notFound() {
        stubCurrentCustomer(customer());
        when(addressRepository.findByIdWithCustomer(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> customerService.setDefaultAddress(99L));
        assertEquals("Address not found", ex.getMessage());
    }

    @Test
    void setDefaultAddress_notOwned_throwsUnauthorized() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Customer other = customer();
        other.setId(77L);
        when(addressRepository.findByIdWithCustomer(3L)).thenReturn(Optional.of(address(3L, other, false)));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> customerService.setDefaultAddress(3L));
        assertEquals("Not your address", ex.getMessage());
    }

    @Test
    void setDefaultAddress_success() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        Address target = address(3L, customer, false);
        Address other = address(4L, customer, true);
        when(addressRepository.findByIdWithCustomer(3L)).thenReturn(Optional.of(target));
        when(addressRepository.findByCustomerId(1L)).thenReturn(List.of(target, other));
        when(addressRepository.save(any(Address.class))).thenAnswer(inv -> inv.getArgument(0));

        AddressResponse response = customerService.setDefaultAddress(3L);

        assertTrue(response.getIsDefault());
        assertTrue(target.getIsDefault());
        assertFalse(other.getIsDefault());
    }

    @Test
    void getWalletBalance() {
        Customer customer = customer();
        stubCurrentCustomer(customer);

        assertEquals(100.0, customerService.getWalletBalance());
    }

    @Test
    void addMoneyToWallet_nullAmount_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class, () -> customerService.addMoneyToWallet(null));
        assertEquals("Amount must be positive", ex.getMessage());
    }

    @Test
    void addMoneyToWallet_zero_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class, () -> customerService.addMoneyToWallet(0.0));
        assertEquals("Amount must be positive", ex.getMessage());
    }

    @Test
    void addMoneyToWallet_negative_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class, () -> customerService.addMoneyToWallet(-10.0));
        assertEquals("Amount must be positive", ex.getMessage());
    }

    @Test
    void addMoneyToWallet_success() {
        Customer customer = customer();
        stubCurrentCustomer(customer);
        when(walletProperties.isAllowDirectTopUp()).thenReturn(true);

        customerService.addMoneyToWallet(50.0);

        verify(walletService).credit(
                eq(customer),
                eq(50.0),
                eq(WalletTransaction.TransactionType.ADJUSTMENT),
                isNull(),
                eq("Direct wallet top-up (dev/admin)"));
    }

    @Test
    void addMoneyToWallet_disabled_throwsBusinessException() {
        when(walletProperties.isAllowDirectTopUp()).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> customerService.addMoneyToWallet(50.0));
        assertTrue(ex.getMessage().contains("Direct wallet credit is disabled"));
    }

    @Test
    void getLoyaltyPoints() {
        Customer customer = customer();
        stubCurrentCustomer(customer);

        assertEquals(50, customerService.getLoyaltyPoints());
    }

    @Test
    void getCustomerById_otherUser_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(2L);
        assertThrows(UnauthorizedException.class, () -> customerService.getCustomerById(1L));
    }

    @Test
    void getCustomerById_notFound() {
        when(securityUtils.getCurrentUserId()).thenReturn(99L);
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> customerService.getCustomerById(99L));
        assertEquals("Customer not found with id: 99", ex.getMessage());
    }

    @Test
    void getCustomerById_success_withCreatedAt() {
        Customer customer = customer();
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(addressRepository.findByCustomerId(1L)).thenReturn(Collections.emptyList());
        when(orderRepository.countByCustomerId(1L)).thenReturn(2L);

        CustomerProfileResponse profile = customerService.getCustomerById(1L);

        assertEquals(1L, profile.getId());
        assertEquals(customer.getCreatedAt().toString(), profile.getCreatedAt());
        assertEquals(2, profile.getTotalOrders());
        assertTrue(profile.getAddresses().isEmpty());
    }

    @Test
    void getCustomerById_nullCreatedAt() {
        Customer customer = customer();
        customer.setCreatedAt(null);
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(addressRepository.findByCustomerId(1L)).thenReturn(Collections.emptyList());
        when(orderRepository.countByCustomerId(1L)).thenReturn(0L);

        CustomerProfileResponse profile = customerService.getCustomerById(1L);

        assertNull(profile.getCreatedAt());
    }
}

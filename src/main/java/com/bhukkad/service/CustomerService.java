package com.bhukkad.service;

import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.CustomerProfileResponse;
import com.bhukkad.dto.response.CustomerResponse;
import com.bhukkad.entity.Customer;

import java.util.List;

public interface CustomerService {
    CustomerProfileResponse getCustomerById(Long id);
    Customer getCurrentCustomer();
    void deleteAccount();

    // Address management
    AddressResponse addAddress(AddressRequest request);
    List<AddressResponse> getAddresses();
    AddressResponse updateAddress(Long addressId, AddressRequest request);
    void deleteAddress(Long addressId);
    AddressResponse setDefaultAddress(Long addressId);

    // Wallet
    Double getWalletBalance();
    void addMoneyToWallet(Double amount);

    // Loyalty points
    Integer getLoyaltyPoints();

    CustomerProfileResponse getProfile();
    CustomerResponse updateProfile(String fullName, String phoneNumber, String profileImageUrl);
}
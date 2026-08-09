package com.bhukkad.controller;

import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CustomerProfileResponse;
import com.bhukkad.dto.response.CustomerResponse;
import com.bhukkad.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<CustomerProfileResponse>> getProfile() {
        CustomerProfileResponse profile = customerService.getProfile();
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @GetMapping("/profile/{profileId}")
    public ResponseEntity<ApiResponse<CustomerProfileResponse>> getProfile(@PathVariable Long profileId) {
        CustomerProfileResponse profile = customerService.getCustomerById(profileId);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<CustomerResponse>> updateProfile(
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String phoneNumber,
            @RequestParam(required = false) String profileImageUrl) {
        CustomerResponse response = customerService.updateProfile(fullName, phoneNumber, profileImageUrl);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", response));
    }

    // No ID parameter - uses current logged-in user from JWT token
    @DeleteMapping("/account")
    public ResponseEntity<ApiResponse<Void>> deleteAccount() {
        customerService.deleteAccount();
        return ResponseEntity.ok(ApiResponse.success("Account deactivated", null));
    }

    @PostMapping("/addresses")
    public ResponseEntity<ApiResponse<AddressResponse>> addAddress(
            @Valid @RequestBody AddressRequest request) {
        AddressResponse address = customerService.addAddress(request);
        return ResponseEntity.ok(ApiResponse.success("Address added", address));
    }

    @GetMapping("/addresses")
    public ResponseEntity<ApiResponse<List<AddressResponse>>> getAddresses() {
        List<AddressResponse> addresses = customerService.getAddresses();
        return ResponseEntity.ok(ApiResponse.success(addresses));
    }

    @PutMapping("/addresses/{addressId}")
    public ResponseEntity<ApiResponse<AddressResponse>> updateAddress(
            @PathVariable Long addressId,
            @Valid @RequestBody AddressRequest request) {
        AddressResponse address = customerService.updateAddress(addressId, request);
        return ResponseEntity.ok(ApiResponse.success("Address updated", address));
    }

    @DeleteMapping("/addresses/{addressId}")
    public ResponseEntity<ApiResponse<Void>> deleteAddress(@PathVariable Long addressId) {
        customerService.deleteAddress(addressId);
        return ResponseEntity.ok(ApiResponse.success("Address deleted", null));
    }

    @PutMapping("/addresses/{addressId}/set-default")
    public ResponseEntity<ApiResponse<AddressResponse>> setDefaultAddress(
            @PathVariable Long addressId) {
        AddressResponse address = customerService.setDefaultAddress(addressId);
        return ResponseEntity.ok(ApiResponse.success("Default address set", address));
    }

    @GetMapping("/wallet/balance")
    public ResponseEntity<ApiResponse<Double>> getWalletBalance() {
        Double balance = customerService.getWalletBalance();
        return ResponseEntity.ok(ApiResponse.success(balance));
    }

    @PostMapping("/wallet/add-money")
    public ResponseEntity<ApiResponse<Void>> addMoneyToWallet(@RequestParam Double amount) {
        customerService.addMoneyToWallet(amount);
        return ResponseEntity.ok(ApiResponse.success("Money added to wallet", null));
    }

    @GetMapping("/loyalty-points")
    public ResponseEntity<ApiResponse<Integer>> getLoyaltyPoints() {
        Integer points = customerService.getLoyaltyPoints();
        return ResponseEntity.ok(ApiResponse.success(points));
    }
}
package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.request.NotificationPreferenceRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CustomerOrderStatsResponse;
import com.bhukkad.dto.response.CustomerProfileResponse;
import com.bhukkad.dto.response.CustomerResponse;
import com.bhukkad.dto.response.FavoriteRestaurantResponse;
import com.bhukkad.dto.response.NotificationPreferenceResponse;
import com.bhukkad.dto.response.ReferralInfoResponse;
import com.bhukkad.referral.ReferralService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CustomerService;
import com.bhukkad.service.DeviceTokenService;
import com.bhukkad.service.FavoriteService;
import com.bhukkad.service.NotificationPreferenceService;
import com.bhukkad.wallet.WalletTopUpService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/customers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Customer", description = "REST endpoints for Customer")
public class CustomerController {

    private final CustomerService customerService;
    private final WalletTopUpService walletTopUpService;
    private final DeviceTokenService deviceTokenService;
    private final FavoriteService favoriteService;
    private final ReferralService referralService;
    private final SecurityUtils securityUtils;
    private final NotificationPreferenceService notificationPreferenceService;

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<CustomerProfileResponse>> getProfile() {
        CustomerProfileResponse profile = customerService.getProfile();
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @GetMapping("/profile/{profileId}")
    @Operation(summary = "Get profile by id")
    public ResponseEntity<ApiResponse<CustomerProfileResponse>> getProfileById(
            @PathVariable @Positive Long profileId) {
        CustomerProfileResponse profile = customerService.getCustomerById(profileId);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }

    @PutMapping("/profile")
    @Operation(summary = "Update profile")
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
    @Operation(summary = "Add address")
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
    @Operation(summary = "Update address")
    public ResponseEntity<ApiResponse<AddressResponse>> updateAddress(
            @PathVariable @Positive Long addressId,
            @Valid @RequestBody AddressRequest request) {
        AddressResponse address = customerService.updateAddress(addressId, request);
        return ResponseEntity.ok(ApiResponse.success("Address updated", address));
    }

    @DeleteMapping("/addresses/{addressId}")
    public ResponseEntity<ApiResponse<Void>> deleteAddress(@PathVariable @Positive Long addressId) {
        customerService.deleteAddress(addressId);
        return ResponseEntity.ok(ApiResponse.success("Address deleted", null));
    }

    @PutMapping("/addresses/{addressId}/set-default")
    @Operation(summary = "Set default address")
    public ResponseEntity<ApiResponse<AddressResponse>> setDefaultAddress(
            @PathVariable @Positive Long addressId) {
        AddressResponse address = customerService.setDefaultAddress(addressId);
        return ResponseEntity.ok(ApiResponse.success("Default address set", address));
    }

    @GetMapping("/wallet/balance")
    public ResponseEntity<ApiResponse<Double>> getWalletBalance() {
        Double balance = customerService.getWalletBalance();
        return ResponseEntity.ok(ApiResponse.success(balance));
    }

    @PostMapping("/wallet/top-up")
    @Operation(summary = "Initiate wallet top up")
    public ResponseEntity<ApiResponse<com.bhukkad.dto.response.PaymentResponse>> initiateWalletTopUp(
            @RequestParam @Positive Double amount,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(ApiResponse.success(
                "Wallet top-up initiated",
                walletTopUpService.initiateTopUp(amount, idempotencyKey)));
    }

    @PostMapping("/device-tokens")
    @Operation(summary = "Register device token")
    public ResponseEntity<ApiResponse<com.bhukkad.dto.response.DeviceTokenResponse>> registerDeviceToken(
            @Valid @RequestBody com.bhukkad.dto.request.DeviceTokenRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Device token registered",
                deviceTokenService.registerToken(request)));
    }

    @DeleteMapping("/device-tokens")
    public ResponseEntity<ApiResponse<Void>> unregisterDeviceToken(@RequestBody com.bhukkad.dto.request.DeviceTokenRequest request) {
        deviceTokenService.unregisterToken(request.getToken());
        return ResponseEntity.ok(ApiResponse.success("Device token removed", null));
    }

    @PostMapping("/wallet/add-money")
    public ResponseEntity<ApiResponse<Void>> addMoneyToWallet(@RequestParam @Positive Double amount) {
        customerService.addMoneyToWallet(amount);
        return ResponseEntity.ok(ApiResponse.success("Money added to wallet", null));
    }

    @GetMapping("/loyalty-points")
    public ResponseEntity<ApiResponse<Integer>> getLoyaltyPoints() {
        Integer points = customerService.getLoyaltyPoints();
        return ResponseEntity.ok(ApiResponse.success(points));
    }

    @GetMapping("/referral")
    public ResponseEntity<ApiResponse<ReferralInfoResponse>> getReferralInfo() {
        ReferralInfoResponse info = referralService.getReferralInfo(securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(info));
    }

    @GetMapping("/favorites")
    public ResponseEntity<ApiResponse<List<FavoriteRestaurantResponse>>> getFavorites() {
        return ResponseEntity.ok(ApiResponse.success(favoriteService.listFavorites()));
    }

    @PostMapping("/favorites/{restaurantId}")
    @Operation(summary = "Add favorite")
    public ResponseEntity<ApiResponse<FavoriteRestaurantResponse>> addFavorite(
            @PathVariable @Positive Long restaurantId) {
        FavoriteRestaurantResponse favorite = favoriteService.addFavorite(restaurantId);
        return ResponseEntity.ok(ApiResponse.success("Restaurant added to favorites", favorite));
    }

    @DeleteMapping("/favorites/{restaurantId}")
    public ResponseEntity<ApiResponse<Void>> removeFavorite(@PathVariable @Positive Long restaurantId) {
        favoriteService.removeFavorite(restaurantId);
        return ResponseEntity.ok(ApiResponse.success("Restaurant removed from favorites", null));
    }

    @GetMapping("/orders/stats")
    public ResponseEntity<ApiResponse<CustomerOrderStatsResponse>> getOrderStats() {
        return ResponseEntity.ok(ApiResponse.success(customerService.getOrderStats()));
    }

    @GetMapping("/notification-preferences")
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> getNotificationPreferences() {
        NotificationPreferenceResponse prefs = notificationPreferenceService.getPreferences(
                securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(prefs));
    }

    @PutMapping("/notification-preferences")
    @Operation(summary = "Update notification preferences")
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> updateNotificationPreferences(
            @RequestBody NotificationPreferenceRequest request) {
        NotificationPreferenceResponse prefs = notificationPreferenceService.updatePreferences(
                securityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success("Preferences updated", prefs));
    }
}
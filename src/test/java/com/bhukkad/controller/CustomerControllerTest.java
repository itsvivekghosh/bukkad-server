package com.bhukkad.controller;

import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.request.DeviceTokenRequest;
import com.bhukkad.dto.request.NotificationPreferenceRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CustomerOrderStatsResponse;
import com.bhukkad.dto.response.CustomerProfileResponse;
import com.bhukkad.dto.response.CustomerResponse;
import com.bhukkad.dto.response.DeviceTokenResponse;
import com.bhukkad.dto.response.FavoriteRestaurantResponse;
import com.bhukkad.dto.response.NotificationPreferenceResponse;
import com.bhukkad.dto.response.PaymentResponse;
import com.bhukkad.dto.response.ReferralInfoResponse;
import com.bhukkad.referral.ReferralService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CustomerService;
import com.bhukkad.service.DeviceTokenService;
import com.bhukkad.service.FavoriteService;
import com.bhukkad.service.NotificationPreferenceService;
import com.bhukkad.wallet.WalletTopUpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("regression")
@ExtendWith(MockitoExtension.class)
public class CustomerControllerTest {

    @Mock
    private CustomerService customerService;
    @Mock
    private WalletTopUpService walletTopUpService;
    @Mock
    private DeviceTokenService deviceTokenService;
    @Mock
    private FavoriteService favoriteService;
    @Mock
    private ReferralService referralService;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private NotificationPreferenceService notificationPreferenceService;

    @InjectMocks
    private CustomerController customerController;

    @Test
    void getProfile_returnsCurrentUserProfile() {
        CustomerProfileResponse profile = new CustomerProfileResponse();
        when(customerService.getProfile()).thenReturn(profile);

        ResponseEntity<ApiResponse<CustomerProfileResponse>> response = customerController.getProfile();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(profile, response.getBody().getData());
        verify(customerService).getProfile();
    }

    @Test
    void getProfileById_returnsProfile() {
        CustomerProfileResponse profile = new CustomerProfileResponse();
        when(customerService.getCustomerById(5L)).thenReturn(profile);

        ResponseEntity<ApiResponse<CustomerProfileResponse>> response = customerController.getProfileById(5L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(profile, response.getBody().getData());
        verify(customerService).getCustomerById(5L);
    }

    @Test
    void updateProfile_returnsUpdatedCustomer() {
        CustomerResponse customerResponse = new CustomerResponse();
        when(customerService.updateProfile("Ada", "9999999999", "img.png")).thenReturn(customerResponse);

        ResponseEntity<ApiResponse<CustomerResponse>> response =
                customerController.updateProfile("Ada", "9999999999", "img.png");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Profile updated", response.getBody().getMessage());
        assertEquals(customerResponse, response.getBody().getData());
    }

    @Test
    void deleteAccount_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = customerController.deleteAccount();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Account deactivated", response.getBody().getMessage());
        verify(customerService).deleteAccount();
    }

    @Test
    void addAddress_returnsCreatedAddress() {
        AddressRequest request = new AddressRequest();
        AddressResponse addressResponse = new AddressResponse();
        when(customerService.addAddress(request)).thenReturn(addressResponse);

        ResponseEntity<ApiResponse<AddressResponse>> response = customerController.addAddress(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Address added", response.getBody().getMessage());
        assertEquals(addressResponse, response.getBody().getData());
    }

    @Test
    void getAddresses_returnsList() {
        List<AddressResponse> addresses = List.of(new AddressResponse());
        when(customerService.getAddresses()).thenReturn(addresses);

        ResponseEntity<ApiResponse<List<AddressResponse>>> response = customerController.getAddresses();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(addresses, response.getBody().getData());
    }

    @Test
    void updateAddress_returnsUpdatedAddress() {
        AddressRequest request = new AddressRequest();
        AddressResponse addressResponse = new AddressResponse();
        when(customerService.updateAddress(3L, request)).thenReturn(addressResponse);

        ResponseEntity<ApiResponse<AddressResponse>> response = customerController.updateAddress(3L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Address updated", response.getBody().getMessage());
        assertEquals(addressResponse, response.getBody().getData());
    }

    @Test
    void deleteAddress_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = customerController.deleteAddress(3L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Address deleted", response.getBody().getMessage());
        verify(customerService).deleteAddress(3L);
    }

    @Test
    void setDefaultAddress_returnsAddress() {
        AddressResponse addressResponse = new AddressResponse();
        when(customerService.setDefaultAddress(3L)).thenReturn(addressResponse);

        ResponseEntity<ApiResponse<AddressResponse>> response = customerController.setDefaultAddress(3L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Default address set", response.getBody().getMessage());
        assertEquals(addressResponse, response.getBody().getData());
    }

    @Test
    void getWalletBalance_returnsBalance() {
        when(customerService.getWalletBalance()).thenReturn(250.5);

        ResponseEntity<ApiResponse<Double>> response = customerController.getWalletBalance();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(250.5, response.getBody().getData());
    }

    @Test
    void addMoneyToWallet_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = customerController.addMoneyToWallet(100.0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Money added to wallet", response.getBody().getMessage());
        verify(customerService).addMoneyToWallet(100.0);
    }

    @Test
    void getLoyaltyPoints_returnsPoints() {
        when(customerService.getLoyaltyPoints()).thenReturn(42);

        ResponseEntity<ApiResponse<Integer>> response = customerController.getLoyaltyPoints();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(42, response.getBody().getData());
    }

    @Test
    void initiateWalletTopUp_forwardsIdempotencyKey() {
        PaymentResponse payment = PaymentResponse.builder().build();
        when(walletTopUpService.initiateTopUp(500.0, "idem-1")).thenReturn(payment);

        ResponseEntity<ApiResponse<PaymentResponse>> response =
                customerController.initiateWalletTopUp(500.0, "idem-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Wallet top-up initiated", response.getBody().getMessage());
        assertEquals(payment, response.getBody().getData());
        verify(walletTopUpService).initiateTopUp(500.0, "idem-1");
    }

    @Test
    void registerDeviceToken_returnsRegisteredToken() {
        DeviceTokenRequest request = new DeviceTokenRequest();
        DeviceTokenResponse tokenResponse = DeviceTokenResponse.builder().build();
        when(deviceTokenService.registerToken(request)).thenReturn(tokenResponse);

        ResponseEntity<ApiResponse<DeviceTokenResponse>> response = customerController.registerDeviceToken(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Device token registered", response.getBody().getMessage());
        assertEquals(tokenResponse, response.getBody().getData());
    }

    @Test
    void unregisterDeviceToken_returnsSuccess() {
        DeviceTokenRequest request = new DeviceTokenRequest();
        request.setToken("tok-1");

        ResponseEntity<ApiResponse<Void>> response = customerController.unregisterDeviceToken(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Device token removed", response.getBody().getMessage());
        verify(deviceTokenService).unregisterToken("tok-1");
    }

    @Test
    void getReferralInfo_usesCurrentUserId() {
        ReferralInfoResponse info = ReferralInfoResponse.builder().build();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(referralService.getReferralInfo(5L)).thenReturn(info);

        ResponseEntity<ApiResponse<ReferralInfoResponse>> response = customerController.getReferralInfo();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(info, response.getBody().getData());
        verify(referralService).getReferralInfo(5L);
    }

    @Test
    void getFavorites_returnsFavorites() {
        List<FavoriteRestaurantResponse> favorites = List.of(FavoriteRestaurantResponse.builder().build());
        when(favoriteService.listFavorites()).thenReturn(favorites);

        ResponseEntity<ApiResponse<List<FavoriteRestaurantResponse>>> response = customerController.getFavorites();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(favorites, response.getBody().getData());
    }

    @Test
    void addFavorite_returnsFavorite() {
        FavoriteRestaurantResponse favorite = FavoriteRestaurantResponse.builder().build();
        when(favoriteService.addFavorite(9L)).thenReturn(favorite);

        ResponseEntity<ApiResponse<FavoriteRestaurantResponse>> response = customerController.addFavorite(9L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Restaurant added to favorites", response.getBody().getMessage());
        assertEquals(favorite, response.getBody().getData());
    }

    @Test
    void removeFavorite_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = customerController.removeFavorite(9L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Restaurant removed from favorites", response.getBody().getMessage());
        verify(favoriteService).removeFavorite(9L);
    }

    @Test
    void getOrderStats_returnsStats() {
        CustomerOrderStatsResponse stats = CustomerOrderStatsResponse.builder().build();
        when(customerService.getOrderStats()).thenReturn(stats);

        ResponseEntity<ApiResponse<CustomerOrderStatsResponse>> response = customerController.getOrderStats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(stats, response.getBody().getData());
    }

    @Test
    void getNotificationPreferences_usesCurrentUserId() {
        NotificationPreferenceResponse prefs = NotificationPreferenceResponse.builder().build();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(notificationPreferenceService.getPreferences(5L)).thenReturn(prefs);

        ResponseEntity<ApiResponse<NotificationPreferenceResponse>> response =
                customerController.getNotificationPreferences();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(prefs, response.getBody().getData());
    }

    @Test
    void updateNotificationPreferences_returnsUpdatedPrefs() {
        NotificationPreferenceRequest request = new NotificationPreferenceRequest();
        NotificationPreferenceResponse prefs = NotificationPreferenceResponse.builder().build();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(notificationPreferenceService.updatePreferences(5L, request)).thenReturn(prefs);

        ResponseEntity<ApiResponse<NotificationPreferenceResponse>> response =
                customerController.updateNotificationPreferences(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Preferences updated", response.getBody().getMessage());
        assertEquals(prefs, response.getBody().getData());
    }
}

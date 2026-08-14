package com.bhukkad.controller;

import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CustomerProfileResponse;
import com.bhukkad.dto.response.CustomerResponse;
import com.bhukkad.service.CustomerService;
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

@ExtendWith(MockitoExtension.class)
class CustomerControllerTest {

    @Mock
    private CustomerService customerService;

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
}

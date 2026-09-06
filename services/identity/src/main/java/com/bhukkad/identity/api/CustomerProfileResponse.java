package com.bhukkad.identity.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProfileResponse {
    private Long id;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String profileImageUrl;
    private Boolean active;
    private Boolean emailVerified;
    private Boolean phoneVerified;
    private Boolean profileCompleted;
    private Integer loyaltyPoints;
    private Double walletBalance;
    private String role;
    private String createdAt;
    private List<AddressResponse> addresses;
    private Integer totalOrders;
}

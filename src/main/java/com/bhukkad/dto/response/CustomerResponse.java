package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerResponse {
    private Long id;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String profileImageUrl;
    private Boolean active;
    private Boolean emailVerified;
    private Integer loyaltyPoints;
    private Double walletBalance;
    private String role;
    private String createdAt;
    private List<AddressResponse> addresses;
}
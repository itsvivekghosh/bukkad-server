package com.bhukkad.identity.api;

import lombok.Data;

@Data
public class UpdateProfileRequest {
    private String fullName;
    private String phoneNumber;
    private String profileImageUrl;
}

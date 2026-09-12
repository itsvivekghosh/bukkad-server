package com.bhukkad.identity.api.dto.request;

import lombok.Data;

@Data
public class UpdateProfileRequest {
    private String fullName;
    private String phoneNumber;
    private String profileImageUrl;
}

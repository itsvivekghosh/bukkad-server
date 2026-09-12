package com.bhukkad.identity.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String refreshToken;
    @Builder.Default
    private String tokenType = "Bearer";
    private Long userId;
    private String email;
    private String phoneNumber;
    private String fullName;
    private String role;

    @Builder.Default
    private boolean mfaRequired = false;

    private String mfaToken;

    @Builder.Default
    @JsonProperty("isNewUser")
    private boolean isNewUser = false;
}

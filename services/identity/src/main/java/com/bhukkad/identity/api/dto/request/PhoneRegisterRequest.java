package com.bhukkad.identity.api.dto.request;

import com.bhukkad.identity.domain.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class PhoneRegisterRequest {
    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be exactly 10 digits")
    private String phoneNumber;

    private User.UserRole role = User.UserRole.CUSTOMER;

    private String password;

    private String otpChannel = "sms";
}

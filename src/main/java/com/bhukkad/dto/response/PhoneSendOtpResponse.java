package com.bhukkad.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for the unified phone sign-in step that sends the OTP.
 *
 * <p>{@link #isNewUser} tells the client whether verification will create a
 * brand-new account or sign in an existing one, so the UI can show the right
 * message (and prompt for profile completion only for new users).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhoneSendOtpResponse {
    private String phoneNumber;
    private String message;
    private int otpExpiryMinutes;
    @Builder.Default
    @JsonProperty("isNewUser")
    private boolean isNewUser = false;
}

package com.bhukkad.dto.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the JSON contract for phone sign-in responses: {@code isNewUser}
 * must be serialized with that exact name. Lombok's boolean getter convention
 * would otherwise emit {@code newUser}, breaking the frontend contract.
 */
class PhoneSignInResponseSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void phoneSendOtpResponse_serializesIsNewUser() throws Exception {
        PhoneSendOtpResponse response = PhoneSendOtpResponse.builder()
                .phoneNumber("9876543210")
                .isNewUser(true)
                .build();

        String json = mapper.writeValueAsString(response);

        assertTrue(json.contains("\"isNewUser\":true"),
                "Expected isNewUser in JSON but got: " + json);
    }

    @Test
    void authResponse_serializesIsNewUser() throws Exception {
        AuthResponse response = AuthResponse.builder()
                .token("jwt")
                .userId(1L)
                .isNewUser(true)
                .build();

        String json = mapper.writeValueAsString(response);

        assertTrue(json.contains("\"isNewUser\":true"),
                "Expected isNewUser in JSON but got: " + json);
    }
}

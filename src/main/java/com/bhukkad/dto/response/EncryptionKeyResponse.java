package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response returned by {@code GET /v1/auth/encryption-key}.
 * Contains the Base64-encoded RSA public key (X.509 SPKI format)
 * and its expiry timestamp (Unix epoch seconds).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EncryptionKeyResponse {
    private String publicKey;
    private long expiresAt;
}

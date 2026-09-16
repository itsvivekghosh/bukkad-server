package com.bhukkad.common.encryption;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Field-level encryption utility for sensitive PII / payment fields that
 * must remain confidential at rest (database) and in messages (Kafka).
 *
 * <p>Uses AES-256-GCM with a single KEK (Key Encryption Key) loaded from
 * environment / vault. Each encryption call generates a random 96-bit IV,
 * so identical plaintexts produce different ciphertexts (non-deterministic).
 *
 * <p>Non-deterministic encryption means encrypted columns cannot be used in
 * {@code WHERE} clauses. For searchable fields, either use a separate
 * deterministic token or a hash column (see {@link DeterministicFieldEncryption}).
 */
public final class FieldEncryption {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int AES_KEY_SIZE = 256;

    private final SecretKey kek;

    public FieldEncryption(String base64Kek) {
        if (base64Kek == null || base64Kek.isBlank()) {
            throw new IllegalArgumentException("app.encryption.kek must be set");
        }
        byte[] decoded = Base64.getDecoder().decode(base64Kek.trim());
        if (decoded.length != AES_KEY_SIZE / 8) {
            throw new IllegalArgumentException(
                    "KEK must be " + (AES_KEY_SIZE / 8) + " bytes (base64 of 32 raw bytes)");
        }
        this.kek = new SecretKeySpec(decoded, "AES");
    }

    /**
     * Encrypts a plaintext string. Returns null if input is null.
     * Output format: base64( iv || ciphertext ).
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    /**
     * Decrypts a base64( iv || ciphertext ) string. Returns null if input is null.
     */
    public String decrypt(String ciphertextBase64) {
        if (ciphertextBase64 == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertextBase64.trim());
            if (combined.length < GCM_IV_LENGTH + 1) {
                throw new IllegalArgumentException("Ciphertext too short");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return new String(cipher.doFinal(ciphertext),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Field decryption failed", e);
        }
    }
}

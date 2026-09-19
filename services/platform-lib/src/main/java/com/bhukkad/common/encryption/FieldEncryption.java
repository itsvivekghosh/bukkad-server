package com.bhukkad.common.encryption;

import com.bhukkad.common.encryption.Encrypted;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
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
 *
 * <p>Use {@link #encryptField(Object, String)} and {@link #decryptField(Object, String)}
 * together with the {@link Encrypted} annotation to transparently round-trip
 * sensitive entity fields through the KEK without leaking plaintext to logs
 * or the database.</p>
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

    /**
     * Encrypts the field named {@code fieldName} on {@code entity} in-place.
     * The field must be of type {@code String} and annotated with {@link Encrypted}.
     * A null value or a field that is not {@link Encrypted} is silently skipped.
     */
    public void encryptField(Object entity, String fieldName) {
        if (entity == null || fieldName == null) return;
        try {
            Field field = findField(entity.getClass(), fieldName);
            field.setAccessible(true);
            if (!field.isAnnotationPresent(Encrypted.class)) return;
            Object current = field.get(entity);
            if (current == null) return;
            if (!(current instanceof String plaintext)) return;
            String encrypted = encrypt(plaintext);
            field.set(entity, encrypted);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to encrypt field '" + fieldName + "' on " + entity.getClass(), e);
        }
    }

    /**
     * Decrypts the field named {@code fieldName} on {@code entity} in-place.
     * The field must be of type {@code String} and annotated with {@link Encrypted}.
     * A null value is returned as-is.
     */
    public String decryptField(Object entity, String fieldName) {
        if (entity == null || fieldName == null) return null;
        try {
            Field field = findField(entity.getClass(), fieldName);
            field.setAccessible(true);
            Object current = field.get(entity);
            if (current == null) return null;
            if (!(current instanceof String ciphertext)) return null;
            return decrypt(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to decrypt field '" + fieldName + "' on " + entity.getClass(), e);
        }
    }

    /**
     * Returns the plaintext value of the {@link Encrypted} field {@code fieldName}
     * on {@code entity} without mutating the entity.
     */
    public String readDecrypted(Object entity, String fieldName) {
        return decryptField(entity, fieldName);
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found in " + clazz);
    }
}

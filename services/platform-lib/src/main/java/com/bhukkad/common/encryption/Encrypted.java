package com.bhukkad.common.encryption;

import java.lang.annotation.*;

/**
 * Marks a JPA entity field whose value must be transparently encrypted at rest
 * and decrypted on read by the {@link EncryptedFieldPersister} / application
 * listener. Applied to PII and financial fields (Aadhaar, PAN, bank account
 * numbers, etc.).
 *
 * <p>Encrypted columns cannot be used in {@code WHERE} / {@code ORDER BY}
 * clauses. For searchable tokens use {@link DeterministicFieldEncryption}
 * alongside this annotation on a companion column.</p>
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Encrypted {

}

package com.bhukkad.common.encryption;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * JPA {@link AttributeConverter} that transparently encrypts/decrypts
 * {@link String} fields at rest using {@link FieldEncryption}.
 *
 * <p>Apply per-field with {@code @Convert(converter = EncryptedStringConverter.class)}.
 * The {@code FieldEncryption} bean must be present in the service context
 * (it lives in {@code platform-lib} and is auto-scanned when the service
 * includes the library on its classpath).
 *
 * <p>Caveats:
 * <ul>
 *   <li>Encrypted values cannot be used in {@code WHERE} clauses.</li>
 *   <li>Do not apply to foreign keys, IDs, or fields used for sorting/filtering.</li>
 *   <li>Existing plaintext rows must be backfilled via a migration before enabling
 *       the converter on a column that already contains data.</li>
 * </ul>
 */
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Autowired
    private FieldEncryption fieldEncryption;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (fieldEncryption == null) {
            // Bean not wired (tests / misconfiguration) — pass through.
            return attribute;
        }
        return fieldEncryption.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (fieldEncryption == null) {
            return dbData;
        }
        return fieldEncryption.decrypt(dbData);
    }
}

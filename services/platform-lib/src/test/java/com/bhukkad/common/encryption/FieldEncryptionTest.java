package com.bhukkad.common.encryption;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptionTest {

    private static final String KEK_BASE64 = Base64.getEncoder()
            .encodeToString(new byte[32]);

    private FieldEncryption fe() {
        return new FieldEncryption(KEK_BASE64);
    }

    @Test
    void roundTrip_encryptDecrypt_returnsOriginal() {
        FieldEncryption enc = fe();
        String plaintext = "aadhaar-1234-5678-9012";
        String ciphertext = enc.encrypt(plaintext);
        assertThat(ciphertext).isNotNull().isNotEqualTo(plaintext);
        assertThat(enc.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void encrypt_null_returnsNull() {
        assertThat(fe().encrypt(null)).isNull();
    }

    @Test
    void decrypt_null_returnsNull() {
        assertThat(fe().decrypt(null)).isNull();
    }

    @Test
    void decrypt_shortCiphertext_throws() {
        assertThatThrownBy(() -> fe().decrypt(Base64.getEncoder().encodeToString(new byte[4])))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidKek_throwsOnConstruction() {
        assertThatThrownBy(() -> new FieldEncryption("not-valid-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongLengthKek_throwsOnConstruction() {
        assertThatThrownBy(() -> new FieldEncryption(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldAnnotations_canBeReadReflectively() throws NoSuchFieldException {
        assertThat(EncryptedFieldSample.class.getDeclaredField("aadhaarNumber")
                .getAnnotation(Encrypted.class)).isNotNull();
        assertThat(EncryptedFieldSample.class.getDeclaredField("panNumber")
                .getAnnotation(Encrypted.class)).isNotNull();
        assertThat(EncryptedFieldSample.class.getDeclaredField("bankAccount")
                .getAnnotation(Encrypted.class)).isNotNull();
        assertThat(EncryptedFieldSample.class.getDeclaredField("notSensitive")
                .getAnnotation(Encrypted.class)).isNull();
    }

    @Test
    void encryptField_encryptsAnnotatedFieldInPlace() {
        FieldEncryption enc = fe();
        EncryptedFieldSample sample = new EncryptedFieldSample();
        sample.aadhaarNumber = "1234-5678-9012";
        sample.bankAccount = "IN00ABCD1234567890";
        enc.encryptField(sample, "aadhaarNumber");
        enc.encryptField(sample, "bankAccount");
        assertThat(sample.aadhaarNumber).isNotEqualTo("1234-5678-9012");
        assertThat(sample.bankAccount).isNotEqualTo("IN00ABCD1234567890");
        assertThat(enc.decryptField(sample, "aadhaarNumber")).isEqualTo("1234-5678-9012");
        assertThat(enc.decryptField(sample, "bankAccount")).isEqualTo("IN00ABCD1234567890");
    }

    @Test
    void encryptField_nullEntity_skipsSilently() {
        fe().encryptField(null, "anything");
    }

    @Test
    void decryptField_nullFieldValue_returnsNull() {
        EncryptedFieldSample sample = new EncryptedFieldSample();
        assertThat(fe().decryptField(sample, "aadhaarNumber")).isNull();
    }

    @Test
    void readDecrypted_returnsPlaintextWithoutMutating() {
        FieldEncryption enc = fe();
        EncryptedFieldSample sample = new EncryptedFieldSample();
        sample.aadhaarNumber = "AADHAAR-SECRET";
        enc.encryptField(sample, "aadhaarNumber");
        String plaintext = enc.readDecrypted(sample, "aadhaarNumber");
        assertThat(plaintext).isEqualTo("AADHAAR-SECRET");
        assertThat(sample.aadhaarNumber).isNotEqualTo("AADHAAR-SECRET");
    }

    @Test
    void encryptField_nonAnnotatedField_skipsSilently() {
        FieldEncryption enc = fe();
        EncryptedFieldSample sample = new EncryptedFieldSample();
        sample.notSensitive = "public-data";
        enc.encryptField(sample, "notSensitive");
        assertThat(sample.notSensitive).isEqualTo("public-data");
    }

    @Test
    void twoEncryptionCalls_produceDifferentCiphertexts() {
        FieldEncryption enc = fe();
        String c1 = enc.encrypt("same-plaintext");
        String c2 = enc.encrypt("same-plaintext");
        assertThat(c1).isNotEqualTo(c2);
        assertThat(enc.decrypt(c1)).isEqualTo("same-plaintext");
        assertThat(enc.decrypt(c2)).isEqualTo("same-plaintext");
    }

    @SuppressWarnings("FieldMayBeFinal")
    static class EncryptedFieldSample {
        @Encrypted
        String aadhaarNumber;
        @Encrypted
        String panNumber;
        @Encrypted
        String bankAccount;
        String notSensitive;
    }
}

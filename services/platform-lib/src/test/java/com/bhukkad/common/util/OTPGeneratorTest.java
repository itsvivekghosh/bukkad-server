package com.bhukkad.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple numeric/alphanumeric OTP generation guarantees.
 */
class OTPGeneratorTest {

    @Test
    void generateOtpOfRequestedLength() {
        assertThat(OTPGenerator.generateOTP(8)).matches("\\d{8}");
        assertThat(OTPGenerator.generateOTP(0)).isEmpty();
    }

    @Test
    void defaultOtpIsSixDigits() {
        assertThat(OTPGenerator.generateOTP()).matches("\\d{6}");
    }

    @Test
    void alphanumericOtpUsesUppercaseDigitAlphabet() {
        String otp = OTPGenerator.generateAlphanumericOTP(24);
        assertThat(otp).matches("[0-9A-Z]{24}");
        assertThat(OTPGenerator.generateAlphanumericOTP(0)).isEmpty();
    }

    @Test
    void sequencesDivergeRandomly() {
        assertThat(OTPGenerator.generateOTP(40)).isNotEqualTo(OTPGenerator.generateOTP(40));
    }
}

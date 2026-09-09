package com.bhukkad.delivery.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTP shape guarantees: length, charset and non-repetition over samples.
 */
class OTPGeneratorTest {

    @Test
    void generateOTP_digitsOfRequestedLength() {
        assertThat(OTPGenerator.generateOTP(4)).matches("\\d{4}");
        assertThat(OTPGenerator.generateOTP(8)).matches("\\d{8}");
    }

    @Test
    void generateOTP_defaultUsesSixDigits() {
        assertThat(OTPGenerator.generateOTP()).matches("\\d{6}");
    }

    @Test
    void generateOTP_zeroLengthIsEmpty() {
        assertThat(OTPGenerator.generateOTP(0)).isEmpty();
    }

    @Test
    void generateOTP_samplesDiffer() {
        assertThat(OTPGenerator.generateOTP(12)).isNotEqualTo(OTPGenerator.generateOTP(12));
    }

    @Test
    void generateAlphanumericOTP_uppercaseBase36Charset() {
        assertThat(OTPGenerator.generateAlphanumericOTP(10)).matches("[0-9A-Z]{10}");
        assertThat(OTPGenerator.generateAlphanumericOTP(0)).isEmpty();
    }
}

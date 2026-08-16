package com.bhukkad.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OTPGeneratorTest {

    @Test
    void generateOTP_usesRequestedLengthAndDigits() {
        String otp = OTPGenerator.generateOTP(4);
        assertEquals(4, otp.length());
        assertTrue(otp.matches("\\d{4}"));
    }

    @Test
    void generateOTP_zeroLength_returnsEmpty() {
        assertEquals("", OTPGenerator.generateOTP(0));
    }

    @Test
    void generateOTP_defaultLengthIsSix() {
        String otp = OTPGenerator.generateOTP();
        assertEquals(Constants.OTP_LENGTH, otp.length());
        assertTrue(otp.matches("\\d{6}"));
    }

    @Test
    void generateAlphanumericOTP_usesAllowedCharset() {
        String otp = OTPGenerator.generateAlphanumericOTP(8);
        assertEquals(8, otp.length());
        assertTrue(otp.matches("[0-9A-Z]{8}"));
    }

    @Test
    void constructor_isPrivate() throws Exception {
        Constructor<OTPGenerator> constructor = OTPGenerator.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}

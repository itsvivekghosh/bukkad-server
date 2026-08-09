package com.bhukkad.util;

import java.security.SecureRandom;

public class OTPGenerator {

    private static final SecureRandom random = new SecureRandom();

    /**
     * Generate a random OTP of specified length
     * @param length Length of OTP
     * @return OTP string
     */
    public static String generateOTP(int length) {
        StringBuilder otp = new StringBuilder();
        for (int i = 0; i < length; i++) {
            otp.append(random.nextInt(10));
        }
        return otp.toString();
    }

    /**
     * Generate default 6-digit OTP
     * @return OTP string
     */
    public static String generateOTP() {
        return generateOTP(Constants.OTP_LENGTH);
    }

    /**
     * Generate alphanumeric OTP
     * @param length Length of OTP
     * @return Alphanumeric OTP string
     */
    public static String generateAlphanumericOTP(int length) {
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder otp = new StringBuilder();
        for (int i = 0; i < length; i++) {
            otp.append(chars.charAt(random.nextInt(chars.length())));
        }
        return otp.toString();
    }

    private OTPGenerator() {
        // Private constructor to prevent instantiation
    }
}
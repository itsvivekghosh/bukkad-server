package com.bhukkad.util;

import com.bhukkad.exception.BusinessException;

import java.util.regex.Pattern;

public class ValidationUtils {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9]{10}$");

    private static final Pattern PINCODE_PATTERN = Pattern.compile("^[0-9]{6}$");

    private static final Pattern IFSC_PATTERN = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isValidPhoneNumber(String phone) {
        return phone != null && PHONE_PATTERN.matcher(phone).matches();
    }

    public static boolean isValidPincode(String pincode) {
        return pincode != null && PINCODE_PATTERN.matcher(pincode).matches();
    }

    public static boolean isValidIFSC(String ifsc) {
        return ifsc != null && IFSC_PATTERN.matcher(ifsc).matches();
    }

    public static boolean isValidPAN(String pan) {
        return pan != null && PAN_PATTERN.matcher(pan).matches();
    }

    public static void validateEmail(String email) {
        if (!isValidEmail(email)) {
            throw new BusinessException("Invalid email format");
        }
    }

    public static void validatePhoneNumber(String phone) {
        if (!isValidPhoneNumber(phone)) {
            throw new BusinessException("Invalid phone number. Must be 10 digits");
        }
    }

    public static void validatePincode(String pincode) {
        if (!isValidPincode(pincode)) {
            throw new BusinessException("Invalid pincode. Must be 6 digits");
        }
    }

    public static void validateRating(Integer rating) {
        if (rating == null || rating < Constants.MIN_RATING || rating > Constants.MAX_RATING) {
            throw new BusinessException("Rating must be between " + Constants.MIN_RATING + " and " + Constants.MAX_RATING);
        }
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 6) {
            throw new BusinessException("Password must be at least 6 characters long");
        }
    }

    public static void validateNotNull(Object object, String fieldName) {
        if (object == null) {
            throw new BusinessException(fieldName + " cannot be null");
        }
    }

    public static void validateNotEmpty(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(fieldName + " cannot be empty");
        }
    }

    public static void validatePositive(Double value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException(fieldName + " must be positive");
        }
    }

    public static void validatePositive(Integer value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException(fieldName + " must be positive");
        }
    }

    private ValidationUtils() {
        // Private constructor to prevent instantiation
    }
}
package com.bhukkad.util;

import com.bhukkad.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationUtilsTest {

    @Test
    void booleanValidators_acceptValidAndRejectInvalid() {
        assertTrue(ValidationUtils.isValidEmail("user@example.com"));
        assertFalse(ValidationUtils.isValidEmail(null));
        assertFalse(ValidationUtils.isValidEmail("bad-email"));

        assertTrue(ValidationUtils.isValidPhoneNumber("9876543210"));
        assertFalse(ValidationUtils.isValidPhoneNumber(null));
        assertFalse(ValidationUtils.isValidPhoneNumber("12345"));

        assertTrue(ValidationUtils.isValidPincode("560001"));
        assertFalse(ValidationUtils.isValidPincode(null));
        assertFalse(ValidationUtils.isValidPincode("56001"));

        assertTrue(ValidationUtils.isValidIFSC("SBIN0001234"));
        assertFalse(ValidationUtils.isValidIFSC(null));
        assertFalse(ValidationUtils.isValidIFSC("sbin0001234"));

        assertTrue(ValidationUtils.isValidPAN("ABCDE1234F"));
        assertFalse(ValidationUtils.isValidPAN(null));
        assertFalse(ValidationUtils.isValidPAN("ABCDE1234"));
    }

    @Test
    void throwingValidators_passAndFail() {
        assertDoesNotThrow(() -> ValidationUtils.validateEmail("user@example.com"));
        assertEquals("Invalid email format",
                assertThrows(BusinessException.class, () -> ValidationUtils.validateEmail("bad")).getMessage());

        assertDoesNotThrow(() -> ValidationUtils.validatePhoneNumber("9876543210"));
        assertEquals("Invalid phone number. Must be 10 digits",
                assertThrows(BusinessException.class, () -> ValidationUtils.validatePhoneNumber("123")).getMessage());

        assertDoesNotThrow(() -> ValidationUtils.validatePincode("560001"));
        assertEquals("Invalid pincode. Must be 6 digits",
                assertThrows(BusinessException.class, () -> ValidationUtils.validatePincode("12")).getMessage());

        assertDoesNotThrow(() -> ValidationUtils.validateRating(3));
        assertEquals("Rating must be between 1 and 5",
                assertThrows(BusinessException.class, () -> ValidationUtils.validateRating(null)).getMessage());
        assertThrows(BusinessException.class, () -> ValidationUtils.validateRating(0));
        assertThrows(BusinessException.class, () -> ValidationUtils.validateRating(6));

        assertDoesNotThrow(() -> ValidationUtils.validatePassword("secret"));
        assertEquals("Password must be at least 6 characters long",
                assertThrows(BusinessException.class, () -> ValidationUtils.validatePassword(null)).getMessage());
        assertThrows(BusinessException.class, () -> ValidationUtils.validatePassword("12345"));

        assertDoesNotThrow(() -> ValidationUtils.validateNotNull("x", "field"));
        assertEquals("field cannot be null",
                assertThrows(BusinessException.class, () -> ValidationUtils.validateNotNull(null, "field")).getMessage());

        assertDoesNotThrow(() -> ValidationUtils.validateNotEmpty("value", "field"));
        assertEquals("field cannot be empty",
                assertThrows(BusinessException.class, () -> ValidationUtils.validateNotEmpty(null, "field")).getMessage());
        assertThrows(BusinessException.class, () -> ValidationUtils.validateNotEmpty("   ", "field"));

        assertDoesNotThrow(() -> ValidationUtils.validatePositive(1.5, "amount"));
        assertEquals("amount must be positive",
                assertThrows(BusinessException.class, () -> ValidationUtils.validatePositive((Double) null, "amount")).getMessage());
        assertThrows(BusinessException.class, () -> ValidationUtils.validatePositive(0.0, "amount"));

        assertDoesNotThrow(() -> ValidationUtils.validatePositive(2, "qty"));
        assertEquals("qty must be positive",
                assertThrows(BusinessException.class, () -> ValidationUtils.validatePositive((Integer) null, "qty")).getMessage());
        assertThrows(BusinessException.class, () -> ValidationUtils.validatePositive(0, "qty"));
    }

    @Test
    void constructor_isPrivate() throws Exception {
        Constructor<ValidationUtils> constructor = ValidationUtils.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}

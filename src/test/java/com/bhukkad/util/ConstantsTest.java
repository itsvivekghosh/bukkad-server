package com.bhukkad.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstantsTest {

    @Test
    void values_matchExpectedDefaults() {
        assertEquals(30, Constants.DEFAULT_DELIVERY_TIME);
        assertEquals(40.0, Constants.DEFAULT_DELIVERY_FEE);
        assertEquals(0.05, Constants.TAX_RATE);
        assertEquals(20, Constants.DEFAULT_PAGE_SIZE);
        assertEquals(100, Constants.MAX_PAGE_SIZE);
        assertEquals(1, Constants.POINTS_PER_HUNDRED);
        assertEquals(10, Constants.POINTS_TO_RUPEE_RATIO);
        assertEquals(10.0, Constants.MAX_DELIVERY_DISTANCE_KM);
        assertEquals(1, Constants.MIN_RATING);
        assertEquals(5, Constants.MAX_RATING);
        assertEquals(5 * 1024 * 1024, Constants.MAX_FILE_SIZE);
        assertArrayEquals(new String[]{"image/jpeg", "image/png", "image/jpg"}, Constants.ALLOWED_IMAGE_TYPES);
        assertEquals(10, Constants.OTP_EXPIRY_MINUTES);
        assertEquals(6, Constants.OTP_LENGTH);
        assertEquals("Operation completed successfully", Constants.SUCCESS_MESSAGE);
        assertEquals("Operation failed", Constants.FAILURE_MESSAGE);
        assertEquals("You are not authorized to perform this action", Constants.UNAUTHORIZED_MESSAGE);
        assertEquals("Resource not found", Constants.NOT_FOUND_MESSAGE);
    }

    @Test
    void constructor_isPrivate() throws Exception {
        Constructor<Constants> constructor = Constants.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}

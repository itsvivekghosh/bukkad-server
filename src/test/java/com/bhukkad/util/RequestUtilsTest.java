package com.bhukkad.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestUtilsTest {

    @Test
    void constructor_isInvoked() {
        new RequestUtils();
    }

    @Test
    void extractToken_validBearerHeader() {
        assertEquals("abc.def.ghi", RequestUtils.extractTokenFromRequestHeaders("Bearer abc.def.ghi"));
    }

    @Test
    void extractToken_nullHeader_throws() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> RequestUtils.extractTokenFromRequestHeaders(null));
        assertEquals("Invalid or missing Authorization header. Use: Bearer <token>", ex.getMessage());
    }

    @Test
    void extractToken_missingBearerPrefix_throws() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> RequestUtils.extractTokenFromRequestHeaders("Token abc"));
        assertEquals("Invalid or missing Authorization header. Use: Bearer <token>", ex.getMessage());
    }
}

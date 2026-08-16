package com.bhukkad.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestUtilsTest {

    /**
     * {@link RequestUtils} is a static-utility holder, so its only constructor is private and
     * must stay that way. Invoking it reflectively keeps the line covered without reopening the
     * class for instantiation.
     */
    @Test
    void constructor_isPrivateAndInvocable() throws Exception {
        Constructor<RequestUtils> constructor = RequestUtils.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        constructor.newInstance();
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

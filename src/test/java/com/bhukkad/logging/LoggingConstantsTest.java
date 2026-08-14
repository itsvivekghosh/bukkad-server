package com.bhukkad.logging;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoggingConstantsTest {

    @Test
    void constants_haveExpectedValues() {
        assertEquals("traceId", LoggingConstants.TRACE_ID);
        assertEquals("PERFORMANCE", LoggingConstants.PERFORMANCE_LOGGER);
        assertEquals("USER_LOGIN", LoggingConstants.EVENT_USER_LOGIN);
        assertEquals("ORDER_CREATED", LoggingConstants.EVENT_ORDER_CREATED);
        assertEquals("PAYMENT_SUCCESS", LoggingConstants.EVENT_PAYMENT_SUCCESS);
    }

    @Test
    void constructor_isPrivate() throws Exception {
        Constructor<LoggingConstants> constructor = LoggingConstants.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}

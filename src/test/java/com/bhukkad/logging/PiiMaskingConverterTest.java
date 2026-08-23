package com.bhukkad.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PiiMaskingConverterTest {

    private final PiiMaskingConverter converter = new PiiMaskingConverter();

    private ILoggingEvent event(String message, Object... args) {
        LoggerContext ctx = new LoggerContext();
        Logger logger = ctx.getLogger("com.bhukkad.test");
        return new LoggingEvent(
                "com.bhukkad.logging.PiiMaskingConverterTest",
                logger, Level.INFO, message, null, args);
    }

    @Test
    void mask_emailIsRedacted() {
        assertEquals("Contact ***@*** for help",
                PiiMaskingConverter.mask("Contact user@example.com for help"));
    }

    @Test
    void mask_emailWithSubdomainAndPlusIsRedacted() {
        assertEquals("***@***",
                PiiMaskingConverter.mask("a.b+c@sub.domain.example.co"));
    }

    @Test
    void mask_phoneIsRedacted() {
        assertEquals("Phone **********",
                PiiMaskingConverter.mask("Phone 9876543210"));
    }

    @Test
    void mask_multiplePiiValues() {
        assertEquals("***@*** / **********",
                PiiMaskingConverter.mask("a.b+c@sub.domain.co / 9876543210"));
    }

    @Test
    void mask_noPii_isUnchanged() {
        assertEquals("Order placed, ref 12345",
                PiiMaskingConverter.mask("Order placed, ref 12345"));
    }

    @Test
    void mask_null_returnsNull() {
        assertNull(PiiMaskingConverter.mask(null));
    }

    @Test
    void convert_masksFormattedMessage() {
        ILoggingEvent evt = event("email {} phone {}", "alice@example.com", "9876543210");
        assertEquals("email ***@*** phone **********", converter.convert(evt));
    }

    @Test
    void convert_withoutPii_isUnchanged() {
        ILoggingEvent evt = event("no pii here");
        assertEquals("no pii here", converter.convert(evt));
    }

    @Test
    void convert_nullMessage_returnsNull() {
        ILoggingEvent evt = event(null, new Object[0]);
        assertNull(converter.convert(evt));
    }
}
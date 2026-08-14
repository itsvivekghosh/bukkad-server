package com.bhukkad.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExceptionClassesTest {

    @Test
    void businessException_messageAndCause() {
        RuntimeException cause = new RuntimeException("root");
        BusinessException withMessage = new BusinessException("bad request");
        BusinessException withCause = new BusinessException("bad request", cause);

        assertEquals("bad request", withMessage.getMessage());
        assertEquals("bad request", withCause.getMessage());
        assertSame(cause, withCause.getCause());
    }

    @Test
    void resourceNotFoundException_messageAndCause() {
        RuntimeException cause = new RuntimeException("root");
        ResourceNotFoundException withMessage = new ResourceNotFoundException("missing");
        ResourceNotFoundException withCause = new ResourceNotFoundException("missing", cause);

        assertEquals("missing", withMessage.getMessage());
        assertEquals("missing", withCause.getMessage());
        assertSame(cause, withCause.getCause());
    }

    @Test
    void unauthorizedException_messageAndCause() {
        RuntimeException cause = new RuntimeException("root");
        UnauthorizedException withMessage = new UnauthorizedException("denied");
        UnauthorizedException withCause = new UnauthorizedException("denied", cause);

        assertEquals("denied", withMessage.getMessage());
        assertEquals("denied", withCause.getMessage());
        assertSame(cause, withCause.getCause());
    }
}

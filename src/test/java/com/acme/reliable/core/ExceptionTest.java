package com.acme.reliable.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExceptionTest {

    @Test
    void testPermanentException() {
        PermanentException ex = new PermanentException("Permanent error");
        assertEquals("Permanent error", ex.getMessage());
        assertTrue(ex instanceof RuntimeException);
    }

    @Test
    void testTransientException() {
        TransientException ex = new TransientException("Transient error");
        assertEquals("Transient error", ex.getMessage());
        assertTrue(ex instanceof RuntimeException);
    }

    @Test
    void testRetryableBusinessException() {
        RetryableBusinessException ex = new RetryableBusinessException("Retry error");
        assertEquals("Retry error", ex.getMessage());
        assertTrue(ex instanceof RuntimeException);
    }
}

package com.acme.reliable.sample;

import com.acme.reliable.core.PermanentException;
import com.acme.reliable.core.TransientException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreateUserHandlerTest {

    private final CreateUserHandler handler = new CreateUserHandler();

    @Test
    void testSuccessfulInvocation() {
        String result = handler.invoke("CreateUser", "{\"username\":\"alice\"}");
        assertNotNull(result);
        assertTrue(result.contains("userId"));
        assertTrue(result.contains("u-123"));
    }

    @Test
    void testPermanentFailure() {
        assertThrows(PermanentException.class, () -> {
            handler.invoke("CreateUser", "{\"failPermanent\":true}");
        });
    }

    @Test
    void testTransientFailure() {
        assertThrows(TransientException.class, () -> {
            handler.invoke("CreateUser", "{\"failTransient\":true}");
        });
    }

    @Test
    void testUnknownCommand() {
        assertThrows(IllegalArgumentException.class, () -> {
            handler.invoke("UnknownCommand", "{}");
        });
    }
}

package com.acme.reliable.integration;

import com.acme.reliable.spi.InboxStore;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = true, rollback = true)
class InboxStoreIntegrationTest {

    @Inject
    InboxStore inboxStore;

    @Test
    void testMarkIfAbsent() {
        String messageId = "msg-" + UUID.randomUUID();

        // First call should return true (inserted)
        assertTrue(inboxStore.markIfAbsent(messageId, "TestHandler"));

        // Second call should return false (already exists)
        assertFalse(inboxStore.markIfAbsent(messageId, "TestHandler"));
    }

    @Test
    void testDifferentHandlersSameMessage() {
        String messageId = "msg-" + UUID.randomUUID();

        // Different handlers can process the same message
        assertTrue(inboxStore.markIfAbsent(messageId, "Handler1"));
        assertTrue(inboxStore.markIfAbsent(messageId, "Handler2"));

        // But same handler cannot process twice
        assertFalse(inboxStore.markIfAbsent(messageId, "Handler1"));
        assertFalse(inboxStore.markIfAbsent(messageId, "Handler2"));
    }
}

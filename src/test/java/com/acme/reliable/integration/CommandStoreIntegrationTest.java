package com.acme.reliable.integration;

import com.acme.reliable.spi.CommandStore;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = true, rollback = true)
class CommandStoreIntegrationTest {

    @Inject
    CommandStore commandStore;

    @Test
    void testSaveAndFind() {
        String idem = "test-idem-" + UUID.randomUUID();
        UUID id = commandStore.savePending(
            "TestCommand",
            idem,
            "test-key",
            "{\"data\":\"test\"}",
            "{\"mode\":\"test\"}"
        );

        assertNotNull(id);

        var found = commandStore.find(id);
        assertTrue(found.isPresent());
        assertEquals("TestCommand", found.get().name());
        assertEquals("test-key", found.get().key());
        assertEquals("PENDING", found.get().status());
    }

    @Test
    void testMarkRunning() {
        String idem = "test-run-" + UUID.randomUUID();
        UUID id = commandStore.savePending(
            "TestCommand",
            idem,
            "test-key",
            "{}",
            "{}"
        );

        commandStore.markRunning(id, Instant.now().plusSeconds(300));
        var found = commandStore.find(id);
        assertTrue(found.isPresent());
        assertEquals("RUNNING", found.get().status());
    }

    @Test
    void testMarkSucceeded() {
        String idem = "test-success-" + UUID.randomUUID();
        UUID id = commandStore.savePending(
            "TestCommand",
            idem,
            "test-key",
            "{}",
            "{}"
        );

        commandStore.markSucceeded(id);
        var found = commandStore.find(id);
        assertTrue(found.isPresent());
        assertEquals("SUCCEEDED", found.get().status());
    }

    @Test
    void testMarkFailed() {
        String idem = "test-fail-" + UUID.randomUUID();
        UUID id = commandStore.savePending(
            "TestCommand",
            idem,
            "test-key",
            "{}",
            "{}"
        );

        commandStore.markFailed(id, "Test error");
        var found = commandStore.find(id);
        assertTrue(found.isPresent());
        assertEquals("FAILED", found.get().status());
    }

    @Test
    void testIdempotencyKey() {
        String idem = "test-idem-unique-" + UUID.randomUUID();
        commandStore.savePending("TestCommand", idem, "test-key", "{}", "{}");

        assertTrue(commandStore.existsByIdempotencyKey(idem));
        assertFalse(commandStore.existsByIdempotencyKey("non-existent"));
    }
}

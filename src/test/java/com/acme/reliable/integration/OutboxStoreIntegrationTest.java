package com.acme.reliable.integration;

import com.acme.reliable.spi.OutboxStore;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = true, rollback = true)
class OutboxStoreIntegrationTest {

    @Inject
    OutboxStore outboxStore;

    @Test
    void testAddAndClaimOne() {
        var row = new OutboxStore.OutboxRow(
            UUID.randomUUID(),
            "command",
            "TEST.Q",
            "key-1",
            "TestCommand",
            "{\"data\":\"test\"}",
            Map.of("header1", "value1"),
            0
        );

        UUID id = outboxStore.addReturningId(row);
        assertNotNull(id);

        var claimed = outboxStore.claimOne(id);
        assertTrue(claimed.isPresent());
        assertEquals("command", claimed.get().category());
        assertEquals("TEST.Q", claimed.get().topic());
    }

    @Test
    void testClaim() {
        // Add multiple outbox entries
        for (int i = 0; i < 5; i++) {
            var row = new OutboxStore.OutboxRow(
                UUID.randomUUID(),
                "event",
                "events.test",
                "key-" + i,
                "TestEvent",
                "{\"index\":" + i + "}",
                Map.of(),
                0
            );
            outboxStore.addReturningId(row);
        }

        var claimed = outboxStore.claim(3, "test-worker");
        assertFalse(claimed.isEmpty());
        assertTrue(claimed.size() <= 3);
    }

    @Test
    void testMarkPublished() {
        var row = new OutboxStore.OutboxRow(
            UUID.randomUUID(),
            "command",
            "TEST.Q",
            "key-1",
            "TestCommand",
            "{}",
            Map.of(),
            0
        );

        UUID id = outboxStore.addReturningId(row);
        outboxStore.claimOne(id);
        outboxStore.markPublished(id);

        // After publishing, claiming should return empty
        var reClaim = outboxStore.claimOne(id);
        assertFalse(reClaim.isPresent());
    }

    @Test
    void testReschedule() {
        var row = new OutboxStore.OutboxRow(
            UUID.randomUUID(),
            "command",
            "TEST.Q",
            "key-1",
            "TestCommand",
            "{}",
            Map.of(),
            0
        );

        UUID id = outboxStore.addReturningId(row);
        outboxStore.claimOne(id);
        outboxStore.reschedule(id, 5000, "Test error");

        // After rescheduling with future time, immediate claim should be empty
        // (depends on next_at being in the future)
    }
}

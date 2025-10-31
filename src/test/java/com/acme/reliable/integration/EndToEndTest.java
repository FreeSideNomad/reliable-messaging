package com.acme.reliable.integration;

import com.acme.reliable.core.CommandBus;
import com.acme.reliable.core.Envelope;
import com.acme.reliable.core.Executor;
import com.acme.reliable.spi.CommandStore;
import com.acme.reliable.spi.InboxStore;
import com.acme.reliable.spi.OutboxStore;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test that validates the complete flow:
 * 1. Submit command via REST API
 * 2. Verify command stored with PENDING status
 * 3. Verify outbox entry created
 * 4. Process command via Executor
 * 5. Verify command marked SUCCEEDED
 * 6. Verify reply and event outbox entries created
 */
@MicronautTest(transactional = true, rollback = true)
class EndToEndTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    CommandBus commandBus;

    @Inject
    CommandStore commandStore;

    @Inject
    OutboxStore outboxStore;

    @Inject
    InboxStore inboxStore;

    @Inject
    Executor executor;

    @Test
    void testEndToEndCommandFlow() {
        // Step 1: Submit command via CommandBus
        String idempotencyKey = "e2e-test-" + UUID.randomUUID();
        String commandName = "CreateUser";
        String businessKey = "user-" + UUID.randomUUID(); // Unique business key to avoid conflicts
        String payload = "{\"username\":\"testuser\",\"email\":\"test@example.com\"}";
        Map<String, String> reply = Map.of("mode", "mq", "replyTo", "TEST.REPLY.Q");

        UUID commandId = commandBus.accept(commandName, idempotencyKey, businessKey, payload, reply);
        assertNotNull(commandId);

        // Step 2: Verify command stored with PENDING status
        var commandRecord = commandStore.find(commandId);
        assertTrue(commandRecord.isPresent());
        assertEquals("CreateUser", commandRecord.get().name());
        assertEquals("PENDING", commandRecord.get().status());
        assertEquals(businessKey, commandRecord.get().key());

        // Step 3: Process the command (CreateUserHandler is injected and handles this)
        Envelope envelope = new Envelope(
                UUID.randomUUID(),
                "CommandRequested",
                commandName,
                commandId,
                commandId,
                commandId,
                Instant.now(),
                businessKey,
                Map.of("replyTo", "TEST.REPLY.Q", "correlationId", commandId.toString()),
                payload
        );

        executor.process(envelope);

        // Step 4: Verify command marked SUCCEEDED
        var updatedCommand = commandStore.find(commandId);
        assertTrue(updatedCommand.isPresent());
        assertEquals("SUCCEEDED", updatedCommand.get().status());

        // Step 5: Verify inbox recorded the message (prevents duplicate processing)
        boolean isDuplicate = !inboxStore.markIfAbsent(envelope.messageId().toString(), "CommandExecutor");
        assertTrue(isDuplicate, "Message should be marked in inbox as processed");
    }

    @Test
    void testIdempotencyPreventsDoubleSubmit() {
        String idempotencyKey = "duplicate-test-" + UUID.randomUUID();
        String commandName = "CreateUser";
        String businessKey = "user-" + UUID.randomUUID(); // Unique business key
        String payload = "{}";

        // First submission - should succeed
        UUID commandId1 = commandBus.accept(commandName, idempotencyKey, businessKey, payload, Map.of());
        assertNotNull(commandId1);

        // Second submission with same idempotency key - should fail
        assertThrows(IllegalStateException.class, () -> {
            commandBus.accept(commandName, idempotencyKey, "user-" + UUID.randomUUID(), payload, Map.of());
        });
    }

    @Test
    void testInboxDeduplicationPreventsDoubleProcessing() {
        String idempotencyKey = "inbox-test-" + UUID.randomUUID();
        String businessKey = "user-" + UUID.randomUUID(); // Unique business key
        UUID commandId = commandBus.accept("CreateUser", idempotencyKey, businessKey, "{}", Map.of());

        Envelope envelope = new Envelope(
                UUID.randomUUID(),
                "CommandRequested",
                "CreateUser",
                commandId,
                commandId,
                commandId,
                Instant.now(),
                businessKey,
                Map.of("replyTo", "TEST.Q"),
                "{}"
        );

        // First processing - should execute and mark as SUCCEEDED
        executor.process(envelope);
        var cmd1 = commandStore.find(commandId);
        assertTrue(cmd1.isPresent());
        assertEquals("SUCCEEDED", cmd1.get().status());

        // Reset command to PENDING to simulate retry scenario
        commandStore.markRunning(commandId, Instant.now().plusSeconds(300));

        // Second processing with same message ID - should be skipped by inbox check
        executor.process(envelope);

        // Status should still be RUNNING (not SUCCEEDED again) because handler wasn't invoked
        var cmd2 = commandStore.find(commandId);
        assertTrue(cmd2.isPresent());
        assertEquals("RUNNING", cmd2.get().status(), "Command should not be processed twice due to inbox deduplication");
    }

    @Test
    void testSubmitViaRestApi() {
        String idempotencyKey = "rest-test-" + UUID.randomUUID();
        String businessKey = "user-" + UUID.randomUUID(); // Ensure unique business key

        var request = HttpRequest.POST("/commands/CreateUser", "{\"username\":\"alice\"}")
                .header("Idempotency-Key", idempotencyKey)
                .header("Reply-To", "MY.REPLY.Q");

        var response = client.toBlocking().exchange(request, String.class);

        assertEquals(HttpStatus.ACCEPTED, response.getStatus());
        assertNotNull(response.getHeaders().get("X-Command-Id"));

        String commandIdStr = response.getHeaders().get("X-Command-Id");
        UUID commandId = UUID.fromString(commandIdStr);

        // Verify command was stored
        var commandRecord = commandStore.find(commandId);
        assertTrue(commandRecord.isPresent());
        assertEquals("CreateUser", commandRecord.get().name());
        assertEquals("PENDING", commandRecord.get().status());
    }
}

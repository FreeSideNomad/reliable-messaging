package com.acme.reliable.core;

import com.acme.reliable.spi.OutboxStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxTest {

    @Test
    void testRowCommandRequested() {
        UUID commandId = UUID.randomUUID();
        Map<String, String> reply = Map.of("mode", "mq", "replyTo", "MY.Q");

        OutboxStore.OutboxRow row = Outbox.rowCommandRequested(
            "CreateUser",
            commandId,
            "user-123",
            "{\"username\":\"alice\"}",
            reply
        );

        assertNotNull(row.id());
        assertEquals("command", row.category());
        assertEquals("APP.CMD.CreateUser.Q", row.topic());
        assertEquals("user-123", row.key());
        assertEquals("CommandRequested", row.type());
        assertEquals("{\"username\":\"alice\"}", row.payload());
        assertEquals(0, row.attempts());

        assertTrue(row.headers().containsKey("commandId"));
        assertEquals(commandId.toString(), row.headers().get("commandId"));
        assertEquals("mq", row.headers().get("mode"));
        assertEquals("MY.Q", row.headers().get("replyTo"));
    }

    @Test
    void testRowKafkaEvent() {
        OutboxStore.OutboxRow row = Outbox.rowKafkaEvent(
            "events.UserCreated",
            "user-456",
            "UserCreated",
            "{\"userId\":\"456\"}"
        );

        assertNotNull(row.id());
        assertEquals("event", row.category());
        assertEquals("events.UserCreated", row.topic());
        assertEquals("user-456", row.key());
        assertEquals("UserCreated", row.type());
        assertEquals("{\"userId\":\"456\"}", row.payload());
        assertEquals(0, row.attempts());
        assertTrue(row.headers().isEmpty());
    }

    @Test
    void testRowMqReply() {
        Envelope env = new Envelope(
            UUID.randomUUID(),
            "CommandRequested",
            "TestCommand",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            "test-key",
            Map.of("replyTo", "CUSTOM.REPLY.Q", "correlationId", "corr-123"),
            "{}"
        );

        OutboxStore.OutboxRow row = Outbox.rowMqReply(
            env,
            "CommandCompleted",
            "{\"result\":\"success\"}"
        );

        assertNotNull(row.id());
        assertEquals("reply", row.category());
        assertEquals("CUSTOM.REPLY.Q", row.topic());
        assertEquals("test-key", row.key());
        assertEquals("CommandCompleted", row.type());
        assertEquals("{\"result\":\"success\"}", row.payload());
        assertEquals(0, row.attempts());

        assertEquals(env.correlationId().toString(), row.headers().get("correlationId"));
    }

    @Test
    void testRowMqReplyDefaultQueue() {
        Envelope env = new Envelope(
            UUID.randomUUID(),
            "CommandRequested",
            "TestCommand",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            "test-key",
            Map.of("correlationId", "corr-123"),
            "{}"
        );

        OutboxStore.OutboxRow row = Outbox.rowMqReply(
            env,
            "CommandFailed",
            "{\"error\":\"test\"}"
        );

        assertEquals("APP.CMD.REPLY.Q", row.topic());
    }
}

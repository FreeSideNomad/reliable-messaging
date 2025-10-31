package com.acme.reliable.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EnvelopeTest {

    @Test
    void testCreateEnvelope() {
        UUID messageId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, String> headers = Map.of("key", "value");

        Envelope envelope = new Envelope(
            messageId,
            "CommandRequested",
            "CreateUser",
            commandId,
            correlationId,
            causationId,
            now,
            "user-123",
            headers,
            "{\"username\":\"alice\"}"
        );

        assertEquals(messageId, envelope.messageId());
        assertEquals("CommandRequested", envelope.type());
        assertEquals("CreateUser", envelope.name());
        assertEquals(commandId, envelope.commandId());
        assertEquals(correlationId, envelope.correlationId());
        assertEquals(causationId, envelope.causationId());
        assertEquals(now, envelope.occurredAt());
        assertEquals("user-123", envelope.key());
        assertEquals(headers, envelope.headers());
        assertEquals("{\"username\":\"alice\"}", envelope.payload());
    }
}

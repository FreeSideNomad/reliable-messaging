package com.acme.reliable.core;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Envelope(
    UUID messageId,
    String type,
    String name,
    UUID commandId,
    UUID correlationId,
    UUID causationId,
    Instant occurredAt,
    String key,
    Map<String,String> headers,
    String payload
) {}

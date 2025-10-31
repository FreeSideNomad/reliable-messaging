package com.acme.reliable.mq;

import com.acme.reliable.core.Envelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Helper class to map JMS messages to Envelope
 */
public final class Mappers {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static Envelope toEnvelope(String text, Message m) throws JMSException {
        try {
            JsonNode node = mapper.readTree(text);

            // Extract headers from JMS message
            Map<String, String> headers = new HashMap<>();
            var enumeration = m.getPropertyNames();
            while (enumeration.hasMoreElements()) {
                String propName = (String) enumeration.nextElement();
                headers.put(propName, m.getStringProperty(propName));
            }

            // Add standard JMS headers
            if (m.getJMSCorrelationID() != null) {
                headers.put("correlationId", m.getJMSCorrelationID());
            }
            if (m.getJMSReplyTo() != null) {
                headers.put("replyTo", m.getJMSReplyTo().toString());
            }

            // Get commandId from headers or generate
            UUID commandId = extractUuid(headers.get("commandId")).orElse(UUID.randomUUID());

            UUID correlationId = extractUuid(headers.get("correlationId")).orElse(commandId);

            UUID messageId = commandId;

            // Extract business key from payload
            String businessKey = headers.getOrDefault(
                "businessKey",
                node.has("key") ? node.get("key").asText() : node.path("businessKey").asText(null)
            );
            if (businessKey == null || businessKey.isBlank()) {
                businessKey = commandId.toString();
            }

            // Extract command name from payload or headers
            String commandName = headers.get("commandName");
            if (commandName == null || commandName.isBlank()) {
                if (node.has("commandName")) {
                    commandName = node.get("commandName").asText();
                } else if (m.getJMSDestination() != null) {
                    commandName = deriveNameFromDestination(m.getJMSDestination().toString());
                } else {
                    commandName = "UnknownCommand";
                }
            }

            return new Envelope(
                messageId,
                "CommandRequested",
                commandName,
                commandId,
                correlationId,
                commandId, // causationId same as commandId for now
                Instant.now(),
                businessKey,
                headers,
                text
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to map JMS message to Envelope", e);
        }
    }

    private static Optional<UUID> extractUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String deriveNameFromDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return "UnknownCommand";
        }
        String cleaned = destination;
        if (cleaned.startsWith("queue:///")) {
            cleaned = cleaned.substring("queue:///".length());
        }
        if (cleaned.endsWith(".Q")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        int idx = cleaned.lastIndexOf('.');
        return idx >= 0 && idx + 1 < cleaned.length() ? cleaned.substring(idx + 1) : cleaned;
    }
}

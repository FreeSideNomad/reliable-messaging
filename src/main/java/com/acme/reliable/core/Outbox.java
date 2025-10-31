package com.acme.reliable.core;

import com.acme.reliable.spi.OutboxStore.OutboxRow;
import java.util.Map;
import java.util.UUID;

public final class Outbox {
    public static OutboxRow rowCommandRequested(String name, UUID id, String key, String payload, Map<String,String> reply) {
        return new OutboxRow(
            UUID.randomUUID(),
            "command",
            "APP.CMD." + name + ".Q",
            key,
            "CommandRequested",
            payload,
            Jsons.merge(
                reply,
                Map.of(
                    "commandId", id.toString(),
                    "commandName", name,
                    "businessKey", key
                )
            ),
            0
        );
    }

    public static OutboxRow rowKafkaEvent(String topic, String key, String type, String payload) {
        return new OutboxRow(
            UUID.randomUUID(),
            "event",
            topic,
            key,
            type,
            payload,
            Map.of(),
            0
        );
    }

    public static OutboxRow rowMqReply(Envelope env, String type, String payload) {
        String replyTo = env.headers().getOrDefault("replyTo", "APP.CMD.REPLY.Q");
        return new OutboxRow(
            UUID.randomUUID(),
            "reply",
            replyTo,
            env.key(),
            type,
            payload,
            Jsons.merge(env.headers(), Map.of("correlationId", env.correlationId().toString())),
            0
        );
    }
}

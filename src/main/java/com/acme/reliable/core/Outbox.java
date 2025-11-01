package com.acme.reliable.core;

import com.acme.reliable.config.MessagingConfig;
import com.acme.reliable.spi.OutboxStore.OutboxRow;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.UUID;

@Singleton
public final class Outbox {

    private final MessagingConfig config;

    public Outbox(MessagingConfig config) {
        this.config = config;
    }

    public OutboxRow rowCommandRequested(String name, UUID id, String key, String payload, Map<String,String> reply) {
        return new OutboxRow(
            UUID.randomUUID(),
            "command",
            config.getQueueNaming().buildCommandQueue(name),
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

    public OutboxRow rowKafkaEvent(String topic, String key, String type, String payload) {
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

    public OutboxRow rowMqReply(Envelope env, String type, String payload) {
        String replyTo = env.headers().getOrDefault("replyTo", config.getQueueNaming().getReplyQueue());
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

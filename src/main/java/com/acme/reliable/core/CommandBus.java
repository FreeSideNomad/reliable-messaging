package com.acme.reliable.core;

import com.acme.reliable.spi.CommandStore;
import com.acme.reliable.spi.OutboxStore;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

@Singleton
public class CommandBus {
    private final CommandStore commands;
    private final OutboxStore outbox;
    private final FastPathPublisher fastPath;

    public CommandBus(CommandStore c, OutboxStore o, FastPathPublisher f) {
        this.commands = c;
        this.outbox = o;
        this.fastPath = f;
    }

    @Transactional
    public UUID accept(String name, String idem, String bizKey, String payload, Map<String,String> reply) {
        if (commands.existsByIdempotencyKey(idem)) {
            throw new IllegalStateException("Duplicate idempotency key");
        }
        UUID id = commands.savePending(name, idem, bizKey, payload, Jsons.toJson(reply));
        UUID outboxId = outbox.addReturningId(Outbox.rowCommandRequested(name, id, bizKey, payload, reply));
        fastPath.registerAfterCommit(outboxId);
        return id;
    }
}

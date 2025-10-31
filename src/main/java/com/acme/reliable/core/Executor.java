package com.acme.reliable.core;

import com.acme.reliable.spi.*;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.time.Instant;

@Singleton
public class Executor {
    private final InboxStore inbox;
    private final CommandStore commands;
    private final OutboxStore outbox;
    private final DlqStore dlq;
    private final HandlerRegistry registry;
    private final FastPathPublisher fastPath;
    private final long leaseSeconds;

    public Executor(InboxStore i, CommandStore c, OutboxStore o, DlqStore d, HandlerRegistry r, FastPathPublisher f) {
        this(i, c, o, d, r, f, 300); // default 5 minutes
    }

    public Executor(InboxStore i, CommandStore c, OutboxStore o, DlqStore d, HandlerRegistry r, FastPathPublisher f, long leaseSeconds) {
        this.inbox = i;
        this.commands = c;
        this.outbox = o;
        this.dlq = d;
        this.registry = r;
        this.fastPath = f;
        this.leaseSeconds = leaseSeconds;
    }

    @Transactional
    public void process(Envelope env) {
        if (!inbox.markIfAbsent(env.messageId().toString(), "CommandExecutor")) {
            return;
        }
        commands.markRunning(env.commandId(), Instant.now().plusSeconds(leaseSeconds));
        try {
            String resultJson = registry.invoke(env.name(), env.payload());
            commands.markSucceeded(env.commandId());
            var replyId = outbox.addReturningId(Outbox.rowMqReply(env, "CommandCompleted", resultJson));
            var eventId = outbox.addReturningId(Outbox.rowKafkaEvent(
                "events." + env.name(),
                env.key(),
                "CommandCompleted",
                Aggregates.snapshot(env.key())
            ));
            fastPath.registerAfterCommit(replyId);
            fastPath.registerAfterCommit(eventId);
        } catch (PermanentException e) {
            commands.markFailed(env.commandId(), e.getMessage());
            dlq.park(env.commandId(), env.name(), env.key(), env.payload(), "FAILED", "Permanent", e.getMessage(), 0, "worker");
            var replyId = outbox.addReturningId(Outbox.rowMqReply(env, "CommandFailed", Jsons.of("error", e.getMessage())));
            var eventId = outbox.addReturningId(Outbox.rowKafkaEvent(
                "events." + env.name(),
                env.key(),
                "CommandFailed",
                Jsons.of("error", e.getMessage())
            ));
            fastPath.registerAfterCommit(replyId);
            fastPath.registerAfterCommit(eventId);
            throw e;
        } catch (RetryableBusinessException | TransientException e) {
            commands.bumpRetry(env.commandId(), e.getMessage());
            throw e;
        }
    }

    public interface HandlerRegistry {
        String invoke(String name, String payload);
    }
}

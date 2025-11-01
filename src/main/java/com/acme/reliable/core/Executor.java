package com.acme.reliable.core;

import com.acme.reliable.config.MessagingConfig;
import com.acme.reliable.config.TimeoutConfig;
import com.acme.reliable.spi.*;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.time.Instant;

@Singleton
public class Executor {
    private final InboxStore inbox;
    private final CommandStore commands;
    private final OutboxStore outboxStore;
    private final Outbox outbox;
    private final DlqStore dlq;
    private final HandlerRegistry registry;
    private final FastPathPublisher fastPath;
    private final MessagingConfig messagingConfig;
    private final long leaseSeconds;

    public Executor(InboxStore i, CommandStore c, OutboxStore os, Outbox o, DlqStore d,
                    HandlerRegistry r, FastPathPublisher f, TimeoutConfig timeoutConfig,
                    MessagingConfig messagingConfig) {
        this.inbox = i;
        this.commands = c;
        this.outboxStore = os;
        this.outbox = o;
        this.dlq = d;
        this.registry = r;
        this.fastPath = f;
        this.messagingConfig = messagingConfig;
        this.leaseSeconds = timeoutConfig.getCommandLeaseSeconds();
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
            var replyId = outboxStore.addReturningId(outbox.rowMqReply(env, "CommandCompleted", resultJson));
            var eventId = outboxStore.addReturningId(outbox.rowKafkaEvent(
                messagingConfig.getTopicNaming().buildEventTopic(env.name()),
                env.key(),
                "CommandCompleted",
                Aggregates.snapshot(env.key())
            ));
            fastPath.registerAfterCommit(replyId);
            fastPath.registerAfterCommit(eventId);
        } catch (PermanentException e) {
            commands.markFailed(env.commandId(), e.getMessage());
            dlq.park(env.commandId(), env.name(), env.key(), env.payload(), "FAILED", "Permanent", e.getMessage(), 0, "worker");
            var replyId = outboxStore.addReturningId(outbox.rowMqReply(env, "CommandFailed", Jsons.of("error", e.getMessage())));
            var eventId = outboxStore.addReturningId(outbox.rowKafkaEvent(
                messagingConfig.getTopicNaming().buildEventTopic(env.name()),
                env.key(),
                "CommandFailed",
                Jsons.of("error", e.getMessage())
            ));
            fastPath.registerAfterCommit(replyId);
            fastPath.registerAfterCommit(eventId);
            // Don't re-throw for permanent failures - we want to commit the DLQ entry and failure state
        } catch (RetryableBusinessException | TransientException e) {
            commands.bumpRetry(env.commandId(), e.getMessage());
            throw e;
        }
    }

    public interface HandlerRegistry {
        String invoke(String name, String payload);
    }
}

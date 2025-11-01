package com.acme.reliable.relay;

import com.acme.reliable.config.TimeoutConfig;
import com.acme.reliable.spi.CommandQueue;
import com.acme.reliable.spi.EventPublisher;
import com.acme.reliable.spi.OutboxStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxRelayTest {

    private OutboxStore outboxStore;
    private CommandQueue commandQueue;
    private EventPublisher eventPublisher;
    private TimeoutConfig timeoutConfig;
    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        outboxStore = mock(OutboxStore.class);
        commandQueue = mock(CommandQueue.class);
        eventPublisher = mock(EventPublisher.class);
        timeoutConfig = mock(TimeoutConfig.class);
        when(timeoutConfig.getMaxBackoffMillis()).thenReturn(300_000L);
        when(timeoutConfig.getOutboxBatchSize()).thenReturn(2000);

        outboxRelay = new OutboxRelay(outboxStore, commandQueue, eventPublisher, timeoutConfig);
    }

    @Test
    void testPublishNowCommand() {
        UUID outboxId = UUID.randomUUID();
        OutboxStore.OutboxRow row = new OutboxStore.OutboxRow(
            outboxId,
            "command",
            "APP.CMD.Test.Q",
            "key-1",
            "CommandRequested",
            "{\"data\":\"test\"}",
            Map.of("commandId", "cmd-123"),
            0
        );

        when(outboxStore.claimOne(outboxId)).thenReturn(Optional.of(row));

        outboxRelay.publishNow(outboxId);

        verify(outboxStore).claimOne(outboxId);
        verify(commandQueue).send("APP.CMD.Test.Q", "{\"data\":\"test\"}", row.headers());
        verify(outboxStore).markPublished(outboxId);
    }

    @Test
    void testPublishNowReply() {
        UUID outboxId = UUID.randomUUID();
        OutboxStore.OutboxRow row = new OutboxStore.OutboxRow(
            outboxId,
            "reply",
            "REPLY.Q",
            "key-1",
            "CommandCompleted",
            "{\"result\":\"ok\"}",
            Map.of("correlationId", "corr-123"),
            0
        );

        when(outboxStore.claimOne(outboxId)).thenReturn(Optional.of(row));

        outboxRelay.publishNow(outboxId);

        verify(commandQueue).send("REPLY.Q", "{\"result\":\"ok\"}", row.headers());
        verify(outboxStore).markPublished(outboxId);
    }

    @Test
    void testPublishNowEvent() {
        UUID outboxId = UUID.randomUUID();
        OutboxStore.OutboxRow row = new OutboxStore.OutboxRow(
            outboxId,
            "event",
            "events.UserCreated",
            "user-123",
            "UserCreated",
            "{\"userId\":\"123\"}",
            Map.of(),
            0
        );

        when(outboxStore.claimOne(outboxId)).thenReturn(Optional.of(row));

        outboxRelay.publishNow(outboxId);

        verify(eventPublisher).publish("events.UserCreated", "user-123", "{\"userId\":\"123\"}", Map.of());
        verify(outboxStore).markPublished(outboxId);
    }

    @Test
    void testPublishNowNotFound() {
        UUID outboxId = UUID.randomUUID();

        when(outboxStore.claimOne(outboxId)).thenReturn(Optional.empty());

        outboxRelay.publishNow(outboxId);

        verify(outboxStore).claimOne(outboxId);
        verify(commandQueue, never()).send(any(), any(), any());
        verify(eventPublisher, never()).publish(any(), any(), any(), any());
        verify(outboxStore, never()).markPublished(any());
    }

    @Test
    void testPublishNowWithError() {
        UUID outboxId = UUID.randomUUID();
        OutboxStore.OutboxRow row = new OutboxStore.OutboxRow(
            outboxId,
            "command",
            "APP.CMD.Test.Q",
            "key-1",
            "CommandRequested",
            "{}",
            Map.of(),
            2 // 2 previous attempts
        );

        when(outboxStore.claimOne(outboxId)).thenReturn(Optional.of(row));
        doThrow(new RuntimeException("Network error")).when(commandQueue).send(any(), any(), any());

        outboxRelay.publishNow(outboxId);

        verify(commandQueue).send(any(), any(), any());
        verify(outboxStore, never()).markPublished(any());
        verify(outboxStore).reschedule(eq(outboxId), anyLong(), contains("Network error"));
    }

    @Test
    void testSweepOnce() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        List<OutboxStore.OutboxRow> rows = List.of(
            new OutboxStore.OutboxRow(id1, "command", "Q1", "k1", "T1", "{}", Map.of(), 0),
            new OutboxStore.OutboxRow(id2, "event", "events.Test", "k2", "T2", "{}", Map.of(), 0)
        );

        when(outboxStore.claim(eq(2000), any())).thenReturn(rows);

        outboxRelay.sweepOnce();

        verify(outboxStore).claim(eq(2000), any());
        verify(commandQueue).send("Q1", "{}", Map.of());
        verify(eventPublisher).publish("events.Test", "k2", "{}", Map.of());
        verify(outboxStore).markPublished(id1);
        verify(outboxStore).markPublished(id2);
    }

    @Test
    void testBackoffCalculation() {
        UUID outboxId = UUID.randomUUID();

        // Test with 5 attempts - should calculate backoff
        OutboxStore.OutboxRow row = new OutboxStore.OutboxRow(
            outboxId,
            "command",
            "Q",
            "k",
            "T",
            "{}",
            Map.of(),
            5
        );

        when(outboxStore.claimOne(outboxId)).thenReturn(Optional.of(row));
        doThrow(new RuntimeException("Error")).when(commandQueue).send(any(), any(), any());

        outboxRelay.publishNow(outboxId);

        // With 5 attempts, backoff should be min(300000, 2^6 * 1000) = 64000
        verify(outboxStore).reschedule(eq(outboxId), eq(64000L), any());
    }
}

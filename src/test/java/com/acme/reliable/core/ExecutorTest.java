package com.acme.reliable.core;

import com.acme.reliable.config.MessagingConfig;
import com.acme.reliable.config.TimeoutConfig;
import com.acme.reliable.spi.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExecutorTest {

    private InboxStore inboxStore;
    private CommandStore commandStore;
    private OutboxStore outboxStore;
    private Outbox outbox;
    private DlqStore dlqStore;
    private Executor.HandlerRegistry registry;
    private FastPathPublisher fastPath;
    private TimeoutConfig timeoutConfig;
    private MessagingConfig messagingConfig;
    private Executor executor;

    @BeforeEach
    void setUp() {
        inboxStore = mock(InboxStore.class);
        commandStore = mock(CommandStore.class);
        outboxStore = mock(OutboxStore.class);
        outbox = mock(Outbox.class);
        dlqStore = mock(DlqStore.class);
        registry = mock(Executor.HandlerRegistry.class);
        fastPath = mock(FastPathPublisher.class);
        timeoutConfig = mock(TimeoutConfig.class);
        messagingConfig = mock(MessagingConfig.class);

        when(timeoutConfig.getCommandLeaseSeconds()).thenReturn(300L);

        MessagingConfig.TopicNaming topicNaming = mock(MessagingConfig.TopicNaming.class);
        when(messagingConfig.getTopicNaming()).thenReturn(topicNaming);
        when(topicNaming.buildEventTopic(any())).thenAnswer(invocation -> "events." + invocation.getArgument(0));

        executor = new Executor(inboxStore, commandStore, outboxStore, outbox, dlqStore, registry, fastPath, timeoutConfig, messagingConfig);
    }

    private Envelope createEnvelope(String name, String payload) {
        return new Envelope(
            UUID.randomUUID(),
            "CommandRequested",
            name,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now(),
            "test-key",
            Map.of("replyTo", "TEST.REPLY.Q"),
            payload
        );
    }

    @Test
    void testProcessSuccess() {
        Envelope env = createEnvelope("TestCommand", "{\"data\":\"test\"}");
        OutboxStore.OutboxRow mockReply = mock(OutboxStore.OutboxRow.class);
        OutboxStore.OutboxRow mockEvent = mock(OutboxStore.OutboxRow.class);

        when(inboxStore.markIfAbsent(env.messageId().toString(), "CommandExecutor")).thenReturn(true);
        when(registry.invoke("TestCommand", "{\"data\":\"test\"}")).thenReturn("{\"result\":\"success\"}");
        when(outbox.rowMqReply(any(), eq("CommandCompleted"), eq("{\"result\":\"success\"}"))).thenReturn(mockReply);
        when(outbox.rowKafkaEvent(any(), any(), eq("CommandCompleted"), any())).thenReturn(mockEvent);
        when(outboxStore.addReturningId(any())).thenReturn(UUID.randomUUID());

        executor.process(env);

        verify(inboxStore).markIfAbsent(env.messageId().toString(), "CommandExecutor");
        verify(commandStore).markRunning(eq(env.commandId()), any(Instant.class));
        verify(registry).invoke("TestCommand", "{\"data\":\"test\"}");
        verify(commandStore).markSucceeded(env.commandId());

        // Verify MQ reply and Kafka event were created
        verify(outbox).rowMqReply(env, "CommandCompleted", "{\"result\":\"success\"}");
        verify(outbox).rowKafkaEvent(eq("events.TestCommand"), eq("test-key"), eq("CommandCompleted"), eq("{\"aggregateKey\":\"test-key\",\"version\":1}"));
        verify(outboxStore, times(2)).addReturningId(any(OutboxStore.OutboxRow.class));
    }

    @Test
    void testProcessDuplicateMessage() {
        Envelope env = createEnvelope("TestCommand", "{}");

        when(inboxStore.markIfAbsent(env.messageId().toString(), "CommandExecutor")).thenReturn(false);

        executor.process(env);

        verify(inboxStore).markIfAbsent(env.messageId().toString(), "CommandExecutor");
        verify(commandStore, never()).markRunning(any(), any());
        verify(registry, never()).invoke(any(), any());
    }

    @Test
    void testProcessPermanentFailure() {
        Envelope env = createEnvelope("TestCommand", "{}");
        OutboxStore.OutboxRow mockReply = mock(OutboxStore.OutboxRow.class);
        OutboxStore.OutboxRow mockEvent = mock(OutboxStore.OutboxRow.class);

        when(inboxStore.markIfAbsent(any(), any())).thenReturn(true);
        when(registry.invoke(any(), any())).thenThrow(new PermanentException("Test permanent error"));
        when(outbox.rowMqReply(any(), eq("CommandFailed"), any())).thenReturn(mockReply);
        when(outbox.rowKafkaEvent(any(), any(), eq("CommandFailed"), any())).thenReturn(mockEvent);
        when(outboxStore.addReturningId(any())).thenReturn(UUID.randomUUID());

        // Permanent failures should NOT throw - they should commit the failure state
        executor.process(env);

        verify(commandStore).markRunning(eq(env.commandId()), any(Instant.class));
        verify(commandStore).markFailed(env.commandId(), "Test permanent error");
        verify(dlqStore).park(
            eq(env.commandId()),
            eq("TestCommand"),
            eq("test-key"),
            eq("{}"),
            eq("FAILED"),
            eq("Permanent"),
            eq("Test permanent error"),
            eq(0),
            eq("worker")
        );

        // Verify failed reply and event
        verify(outbox).rowMqReply(any(), eq("CommandFailed"), any());
        verify(outbox).rowKafkaEvent(any(), any(), eq("CommandFailed"), any());
        verify(outboxStore, times(2)).addReturningId(any(OutboxStore.OutboxRow.class));
    }

    @Test
    void testProcessTransientFailure() {
        Envelope env = createEnvelope("TestCommand", "{}");

        when(inboxStore.markIfAbsent(any(), any())).thenReturn(true);
        when(registry.invoke(any(), any())).thenThrow(new TransientException("Temporary failure"));

        assertThrows(TransientException.class, () -> {
            executor.process(env);
        });

        verify(commandStore).markRunning(eq(env.commandId()), any(Instant.class));
        verify(commandStore).bumpRetry(env.commandId(), "Temporary failure");
        verify(commandStore, never()).markFailed(any(), any());
        verify(dlqStore, never()).park(any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void testProcessRetryableBusinessException() {
        Envelope env = createEnvelope("TestCommand", "{}");

        when(inboxStore.markIfAbsent(any(), any())).thenReturn(true);
        when(registry.invoke(any(), any())).thenThrow(new RetryableBusinessException("Business retry"));

        assertThrows(RetryableBusinessException.class, () -> {
            executor.process(env);
        });

        verify(commandStore).markRunning(eq(env.commandId()), any(Instant.class));
        verify(commandStore).bumpRetry(env.commandId(), "Business retry");
        verify(commandStore, never()).markFailed(any(), any());
    }

    @Test
    void testProcessCreatesCorrectReply() {
        Envelope env = createEnvelope("TestCommand", "{}");

        OutboxStore.OutboxRow expectedReply = new OutboxStore.OutboxRow(
            UUID.randomUUID(),
            "reply",
            "TEST.REPLY.Q",
            "test-key",
            "CommandCompleted",
            "{\"userId\":\"123\"}",
            Map.of(),
            0
        );

        OutboxStore.OutboxRow expectedEvent = new OutboxStore.OutboxRow(
            UUID.randomUUID(),
            "event",
            "events.TestCommand",
            "test-key",
            "CommandCompleted",
            "{\"aggregateKey\":\"test-key\",\"version\":1}",
            Map.of(),
            0
        );

        when(inboxStore.markIfAbsent(any(), any())).thenReturn(true);
        when(registry.invoke(any(), any())).thenReturn("{\"userId\":\"123\"}");
        when(outbox.rowMqReply(any(), eq("CommandCompleted"), eq("{\"userId\":\"123\"}"))).thenReturn(expectedReply);
        when(outbox.rowKafkaEvent(any(), any(), eq("CommandCompleted"), any())).thenReturn(expectedEvent);
        when(outboxStore.addReturningId(any())).thenReturn(UUID.randomUUID());

        ArgumentCaptor<OutboxStore.OutboxRow> captor = ArgumentCaptor.forClass(OutboxStore.OutboxRow.class);

        executor.process(env);

        verify(outboxStore, times(2)).addReturningId(captor.capture());

        // First should be reply, second should be event
        OutboxStore.OutboxRow reply = captor.getAllValues().get(0);
        OutboxStore.OutboxRow event = captor.getAllValues().get(1);

        assertEquals("reply", reply.category());
        assertEquals("TEST.REPLY.Q", reply.topic());
        assertEquals("CommandCompleted", reply.type());
        assertEquals("{\"userId\":\"123\"}", reply.payload());

        assertEquals("event", event.category());
        assertEquals("events.TestCommand", event.topic());
        assertEquals("CommandCompleted", event.type());
        assertEquals("{\"aggregateKey\":\"test-key\",\"version\":1}", event.payload());
    }
}

package com.acme.reliable.core;

import com.acme.reliable.spi.CommandStore;
import com.acme.reliable.spi.OutboxStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommandBusTest {

    private CommandStore commandStore;
    private OutboxStore outboxStore;
    private Outbox outbox;
    private FastPathPublisher fastPath;
    private CommandBus commandBus;

    @BeforeEach
    void setUp() {
        commandStore = mock(CommandStore.class);
        outboxStore = mock(OutboxStore.class);
        outbox = mock(Outbox.class);
        fastPath = mock(FastPathPublisher.class);
        commandBus = new CommandBus(commandStore, outboxStore, outbox, fastPath);
    }

    @Test
    void testAcceptCommand() {
        UUID commandId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        OutboxStore.OutboxRow mockRow = mock(OutboxStore.OutboxRow.class);

        when(commandStore.existsByIdempotencyKey("test-idem")).thenReturn(false);
        when(commandStore.savePending(any(), any(), any(), any(), any())).thenReturn(commandId);
        when(outbox.rowCommandRequested(any(), any(), any(), any(), any())).thenReturn(mockRow);
        when(outboxStore.addReturningId(any())).thenReturn(outboxId);

        UUID result = commandBus.accept(
            "TestCommand",
            "test-idem",
            "test-key",
            "{\"data\":\"test\"}",
            Map.of("mode", "test")
        );

        assertEquals(commandId, result);

        verify(commandStore).existsByIdempotencyKey("test-idem");
        verify(commandStore).savePending(
            eq("TestCommand"),
            eq("test-idem"),
            eq("test-key"),
            eq("{\"data\":\"test\"}"),
            any()
        );
        verify(outbox).rowCommandRequested(eq("TestCommand"), eq(commandId), eq("test-key"), eq("{\"data\":\"test\"}"), any());
        verify(outboxStore).addReturningId(mockRow);
        verify(fastPath).registerAfterCommit(outboxId);
    }

    @Test
    void testAcceptCommandDuplicateIdempotencyKey() {
        when(commandStore.existsByIdempotencyKey("duplicate-key")).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> {
            commandBus.accept(
                "TestCommand",
                "duplicate-key",
                "test-key",
                "{}",
                Map.of()
            );
        });

        verify(commandStore).existsByIdempotencyKey("duplicate-key");
        verify(commandStore, never()).savePending(any(), any(), any(), any(), any());
        verify(outboxStore, never()).addReturningId(any());
        verify(fastPath, never()).registerAfterCommit(any());
    }

    @Test
    void testAcceptCommandCreatesOutboxRow() {
        UUID commandId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();

        when(commandStore.existsByIdempotencyKey(any())).thenReturn(false);
        when(commandStore.savePending(any(), any(), any(), any(), any())).thenReturn(commandId);
        when(outboxStore.addReturningId(any())).thenReturn(outboxId);

        ArgumentCaptor<OutboxStore.OutboxRow> outboxCaptor = ArgumentCaptor.forClass(OutboxStore.OutboxRow.class);

        // Create a real row to return from the mock
        OutboxStore.OutboxRow expectedRow = new OutboxStore.OutboxRow(
            UUID.randomUUID(),
            "command",
            "APP.CMD.CreateUser.Q",
            "user-456",
            "CommandRequested",
            "{\"username\":\"alice\"}",
            Map.of("commandId", commandId.toString(), "commandName", "CreateUser", "businessKey", "user-456", "replyTo", "MY.REPLY.Q"),
            0
        );

        when(outbox.rowCommandRequested(any(), any(), any(), any(), any())).thenReturn(expectedRow);

        commandBus.accept(
            "CreateUser",
            "idem-123",
            "user-456",
            "{\"username\":\"alice\"}",
            Map.of("replyTo", "MY.REPLY.Q")
        );

        verify(outbox).rowCommandRequested(
            eq("CreateUser"),
            eq(commandId),
            eq("user-456"),
            eq("{\"username\":\"alice\"}"),
            any()
        );
        verify(outboxStore).addReturningId(outboxCaptor.capture());

        OutboxStore.OutboxRow row = outboxCaptor.getValue();
        assertEquals("command", row.category());
        assertEquals("APP.CMD.CreateUser.Q", row.topic());
        assertEquals("user-456", row.key());
        assertEquals("CommandRequested", row.type());
        assertEquals("{\"username\":\"alice\"}", row.payload());
        assertTrue(row.headers().containsKey("commandId"));
        assertEquals("CreateUser", row.headers().get("commandName"));
        assertEquals("user-456", row.headers().get("businessKey"));
        assertTrue(row.headers().containsKey("replyTo"));
    }
}

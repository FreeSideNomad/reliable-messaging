package com.acme.reliable.core;

import com.acme.reliable.relay.OutboxRelay;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.TransactionStatus;
import io.micronaut.transaction.support.TransactionSynchronization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class FastPathPublisherTest {

    private TransactionOperations<Connection> transactionOps;
    private TransactionStatus<Connection> transactionStatus;
    private OutboxRelay relay;
    private FastPathPublisher fastPath;

    @BeforeEach
    void setUp() {
        transactionOps = mock(TransactionOperations.class);
        transactionStatus = mock(TransactionStatus.class);
        relay = mock(OutboxRelay.class);
        fastPath = new FastPathPublisher(transactionOps, relay);
    }

    @Test
    void testRegisterAfterCommitRegistersSync() {
        UUID outboxId = UUID.randomUUID();
        when(transactionOps.findTransactionStatus()).thenReturn((Optional) Optional.of(transactionStatus));

        fastPath.registerAfterCommit(outboxId);

        verify(transactionStatus).registerSynchronization(any(TransactionSynchronization.class));
    }

    @Test
    void testAfterCommitPublishes() {
        UUID outboxId = UUID.randomUUID();
        when(transactionOps.findTransactionStatus()).thenReturn((Optional) Optional.of(transactionStatus));
        ArgumentCaptor<TransactionSynchronization> captor = ArgumentCaptor.forClass(TransactionSynchronization.class);

        fastPath.registerAfterCommit(outboxId);

        verify(transactionStatus).registerSynchronization(captor.capture());

        TransactionSynchronization sync = captor.getValue();
        sync.afterCommit();

        verify(relay).publishNow(outboxId);
    }

    @Test
    void testAfterCommitSwallowsExceptions() {
        UUID outboxId = UUID.randomUUID();
        when(transactionOps.findTransactionStatus()).thenReturn((Optional) Optional.of(transactionStatus));
        ArgumentCaptor<TransactionSynchronization> captor = ArgumentCaptor.forClass(TransactionSynchronization.class);

        doThrow(new RuntimeException("Network error")).when(relay).publishNow(any());

        fastPath.registerAfterCommit(outboxId);

        verify(transactionStatus).registerSynchronization(captor.capture());

        TransactionSynchronization sync = captor.getValue();

        // Should not throw
        sync.afterCommit();

        verify(relay).publishNow(outboxId);
    }

    @Test
    void testNoOpWhenNoTransactionActive() {
        UUID outboxId = UUID.randomUUID();
        when(transactionOps.findTransactionStatus()).thenReturn(Optional.empty());

        fastPath.registerAfterCommit(outboxId);

        verify(transactionStatus, never()).registerSynchronization(any());
        verifyNoInteractions(relay);
    }
}

package com.acme.reliable.core;

import com.acme.reliable.relay.OutboxRelay;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.mockito.Mockito.*;

class FastPathPublisherTest {

    private TransactionSynchronizationRegistry tsr;
    private OutboxRelay relay;
    private FastPathPublisher fastPath;

    @BeforeEach
    void setUp() {
        tsr = mock(TransactionSynchronizationRegistry.class);
        relay = mock(OutboxRelay.class);
        fastPath = new FastPathPublisher(tsr, relay);
    }

    @Test
    void testRegisterAfterCommitRegistersSync() {
        UUID outboxId = UUID.randomUUID();

        fastPath.registerAfterCommit(outboxId);

        verify(tsr).registerInterposedSynchronization(any(Synchronization.class));
    }

    @Test
    void testAfterCompletionPublishesOnCommit() {
        UUID outboxId = UUID.randomUUID();
        ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);

        fastPath.registerAfterCommit(outboxId);

        verify(tsr).registerInterposedSynchronization(captor.capture());

        Synchronization sync = captor.getValue();
        sync.afterCompletion(Status.STATUS_COMMITTED);

        verify(relay).publishNow(outboxId);
    }

    @Test
    void testAfterCompletionDoesNotPublishOnRollback() {
        UUID outboxId = UUID.randomUUID();
        ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);

        fastPath.registerAfterCommit(outboxId);

        verify(tsr).registerInterposedSynchronization(captor.capture());

        Synchronization sync = captor.getValue();
        sync.afterCompletion(Status.STATUS_ROLLEDBACK);

        verify(relay, never()).publishNow(any());
    }

    @Test
    void testAfterCompletionSwallowsExceptions() {
        UUID outboxId = UUID.randomUUID();
        ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);

        doThrow(new RuntimeException("Network error")).when(relay).publishNow(any());

        fastPath.registerAfterCommit(outboxId);

        verify(tsr).registerInterposedSynchronization(captor.capture());

        Synchronization sync = captor.getValue();

        // Should not throw
        sync.afterCompletion(Status.STATUS_COMMITTED);

        verify(relay).publishNow(outboxId);
    }

    @Test
    void testBeforeCompletionDoesNothing() {
        UUID outboxId = UUID.randomUUID();
        ArgumentCaptor<Synchronization> captor = ArgumentCaptor.forClass(Synchronization.class);

        fastPath.registerAfterCommit(outboxId);

        verify(tsr).registerInterposedSynchronization(captor.capture());

        Synchronization sync = captor.getValue();

        // Should not throw
        sync.beforeCompletion();

        verifyNoInteractions(relay);
    }
}

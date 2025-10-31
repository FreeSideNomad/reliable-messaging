package com.acme.reliable.core;

import com.acme.reliable.relay.OutboxRelay;
import jakarta.inject.Singleton;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.UUID;

@Singleton
public class FastPathPublisher {
    private final TransactionSynchronizationRegistry tsr;
    private final OutboxRelay relay;

    public FastPathPublisher(TransactionSynchronizationRegistry tsr, OutboxRelay relay) {
        this.tsr = tsr;
        this.relay = relay;
    }

    public void registerAfterCommit(UUID outboxId) {
        tsr.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                    try {
                        relay.publishNow(outboxId);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }
}

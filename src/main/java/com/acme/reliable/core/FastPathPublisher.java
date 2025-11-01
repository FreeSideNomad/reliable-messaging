package com.acme.reliable.core;

import com.acme.reliable.relay.OutboxRelay;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.support.TransactionSynchronization;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.UUID;

@Singleton
public class FastPathPublisher {
    private final TransactionOperations<Connection> transactionOps;
    private final OutboxRelay relay;

    public FastPathPublisher(TransactionOperations<Connection> transactionOps, OutboxRelay relay) {
        this.transactionOps = transactionOps;
        this.relay = relay;
    }

    public void registerAfterCommit(UUID outboxId) {
        transactionOps.findTransactionStatus().ifPresent(status -> {
            status.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        relay.publishNow(outboxId);
                    } catch (Exception ignored) {
                    }
                }
            });
        });
    }
}

package com.acme.reliable.test;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock implementation of TransactionSynchronizationRegistry for testing.
 * Stores synchronizations but doesn't execute them automatically,
 * allowing tests to verify outbox state before fast-path publishing.
 */
@Singleton
@Requires(env = Environment.TEST)
public class MockTransactionSynchronizationRegistry implements TransactionSynchronizationRegistry {

    private final List<Synchronization> synchronizations = new ArrayList<>();

    @Override
    public void registerInterposedSynchronization(Synchronization synchronization) {
        // Store but don't execute - allows tests to verify state before publishing
        synchronizations.add(synchronization);
    }

    /**
     * Execute all registered synchronizations with COMMITTED status.
     * For testing purposes.
     */
    public void executeAfterCommit() {
        synchronizations.forEach(sync -> sync.afterCompletion(jakarta.transaction.Status.STATUS_COMMITTED));
        synchronizations.clear();
    }

    @Override
    public Object getResource(Object o) {
        return null;
    }

    @Override
    public boolean getRollbackOnly() {
        return false;
    }

    @Override
    public void putResource(Object o, Object o1) {
    }

    @Override
    public void setRollbackOnly() {
    }

    @Override
    public int getTransactionStatus() {
        return jakarta.transaction.Status.STATUS_ACTIVE;
    }

    @Override
    public Object getTransactionKey() {
        return "test-transaction";
    }
}

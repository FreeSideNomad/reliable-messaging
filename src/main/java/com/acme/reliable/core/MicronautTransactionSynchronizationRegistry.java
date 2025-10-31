package com.acme.reliable.core;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple implementation of TransactionSynchronizationRegistry for Micronaut Data JDBC.
 * Uses thread-local storage to track transaction synchronizations.
 */
@Singleton
@Requires(missingBeans = TransactionSynchronizationRegistry.class)
public class MicronautTransactionSynchronizationRegistry implements TransactionSynchronizationRegistry {

    private final ThreadLocal<List<Synchronization>> synchronizations = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<Map<Object, Object>> resources = ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> rollbackOnly = ThreadLocal.withInitial(() -> false);

    public void beginTransaction() {
        active.set(true);
        synchronizations.get().clear();
        resources.get().clear();
        rollbackOnly.set(false);
    }

    public void commitTransaction() {
        try {
            List<Synchronization> syncs = synchronizations.get();
            // Call beforeCompletion
            for (Synchronization sync : syncs) {
                try {
                    sync.beforeCompletion();
                } catch (Exception e) {
                    // Log but continue
                }
            }
            // Call afterCompletion with committed status
            for (Synchronization sync : syncs) {
                try {
                    sync.afterCompletion(Status.STATUS_COMMITTED);
                } catch (Exception e) {
                    // Log but continue
                }
            }
        } finally {
            cleanup();
        }
    }

    public void rollbackTransaction() {
        try {
            List<Synchronization> syncs = synchronizations.get();
            for (Synchronization sync : syncs) {
                try {
                    sync.afterCompletion(Status.STATUS_ROLLEDBACK);
                } catch (Exception e) {
                    // Log but continue
                }
            }
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        active.set(false);
        synchronizations.get().clear();
        resources.get().clear();
        rollbackOnly.set(false);
    }

    @Override
    public Object getTransactionKey() {
        return active.get() ? Thread.currentThread().getId() : null;
    }

    @Override
    public void putResource(Object key, Object value) {
        if (active.get()) {
            resources.get().put(key, value);
        }
    }

    @Override
    public Object getResource(Object key) {
        return active.get() ? resources.get().get(key) : null;
    }

    @Override
    public void registerInterposedSynchronization(Synchronization sync) {
        if (active.get()) {
            synchronizations.get().add(sync);
        }
    }

    @Override
    public int getTransactionStatus() {
        return active.get() ? Status.STATUS_ACTIVE : Status.STATUS_NO_TRANSACTION;
    }

    @Override
    public void setRollbackOnly() {
        if (active.get()) {
            rollbackOnly.set(true);
        }
    }

    @Override
    public boolean getRollbackOnly() {
        return active.get() && rollbackOnly.get();
    }
}

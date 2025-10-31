package com.acme.reliable.spi;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommandStore {
    UUID savePending(String name, String idem, String businessKey, String payload, String replyJson);
    Optional<Record> find(UUID id);
    void markRunning(UUID id, Instant leaseUntil);
    void markSucceeded(UUID id);
    void markFailed(UUID id, String error);
    void bumpRetry(UUID id, String error);
    void markTimedOut(UUID id, String reason);
    boolean existsByIdempotencyKey(String k);

    record Record(UUID id, String name, String key, String payload, String status, String reply) {}
}

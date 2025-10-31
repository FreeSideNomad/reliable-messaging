package com.acme.reliable.core;

import jakarta.inject.Singleton;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Singleton
public class ResponseRegistry {
    private final Map<UUID, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<String> register(UUID commandId) {
        var future = new CompletableFuture<String>();
        pending.put(commandId, future);

        // Auto-cleanup after timeout to prevent memory leaks
        future.orTimeout(2, TimeUnit.SECONDS)
               .whenComplete((result, error) -> pending.remove(commandId));

        return future;
    }

    public void complete(UUID commandId, String response) {
        var future = pending.remove(commandId);
        if (future != null && !future.isDone()) {
            future.complete(response);
        }
    }

    public void fail(UUID commandId, String error) {
        var future = pending.remove(commandId);
        if (future != null && !future.isDone()) {
            future.completeExceptionally(new RuntimeException(error));
        }
    }
}

package com.acme.reliable.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface OutboxStore {
    UUID addReturningId(OutboxRow row);
    Optional<OutboxRow> claimOne(UUID id);
    List<OutboxRow> claim(int max, String claimer);
    void markPublished(UUID id);
    void reschedule(UUID id, long backoffMillis, String error);

    record OutboxRow(
        UUID id,
        String category,
        String topic,
        String key,
        String type,
        String payload,
        Map<String,String> headers,
        int attempts
    ) {}
}

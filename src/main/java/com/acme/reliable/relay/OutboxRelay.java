package com.acme.reliable.relay;

import com.acme.reliable.spi.OutboxStore;
import com.acme.reliable.spi.CommandQueue;
import com.acme.reliable.spi.EventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.UUID;

@Singleton
public class OutboxRelay {
    private final OutboxStore store;
    private final CommandQueue mq;
    private final EventPublisher kafka;

    public OutboxRelay(OutboxStore s, CommandQueue m, EventPublisher k) {
        this.store = s;
        this.mq = m;
        this.kafka = k;
    }

    public void publishNow(UUID id) {
        store.claimOne(id).ifPresent(this::sendAndMark);
    }

    @Scheduled(fixedDelay = "30s")
    void sweepOnce() {
        List<OutboxStore.OutboxRow> rows = store.claim(500, host());
        rows.forEach(this::sendAndMark);
    }

    private void sendAndMark(OutboxStore.OutboxRow r) {
        try {
            switch (r.category()) {
                case "command", "reply" -> mq.send(r.topic(), r.payload(), r.headers());
                case "event" -> kafka.publish(r.topic(), r.key(), r.payload(), r.headers());
                default -> throw new IllegalArgumentException("Unknown category " + r.category());
            }
            store.markPublished(r.id());
        } catch (Exception e) {
            long backoff = Math.min(300_000L, (long)Math.pow(2, Math.max(1, r.attempts() + 1)) * 1000L);
            store.reschedule(r.id(), backoff, e.toString());
        }
    }

    private String host() {
        try {
            return java.net.InetAddress.getLoopbackAddress().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}

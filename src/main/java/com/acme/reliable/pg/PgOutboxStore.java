package com.acme.reliable.pg;

import com.acme.reliable.spi.OutboxStore;
import com.acme.reliable.core.Jsons;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import java.sql.*;
import java.util.*;

/**
 * OutboxStore implementation using hybrid approach:
 * - Repository for simple updates (markPublished, reschedule)
 * - Manual JDBC for complex RETURNING queries (claim operations)
 * - TransactionOperations ensures all operations join existing transactions
 */
@Singleton
public class PgOutboxStore implements OutboxStore {
    private final OutboxRepository repository;
    private final TransactionOperations<Connection> transactionOps;
    private final String hostname;

    public PgOutboxStore(OutboxRepository repository, TransactionOperations<Connection> transactionOps) {
        this.repository = repository;
        this.transactionOps = transactionOps;
        try {
            this.hostname = java.net.InetAddress.getLoopbackAddress().getHostName();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get hostname", e);
        }
    }

    @Override
    public UUID addReturningId(OutboxRow r) {
        var id = r.id() != null ? r.id() : UUID.randomUUID();
        String headersJson = Jsons.toJson(r.headers());

        return repository.addReturningId(
            id,
            r.category(),
            r.topic(),
            r.key(),
            r.type(),
            r.payload(),
            headersJson
        );
    }

    @Override
    public Optional<OutboxRow> claimOne(UUID id) {
        // Use current transaction's connection if available
        return transactionOps.findTransactionStatus()
            .map(status -> {
                try {
                    Connection conn = (Connection) status.getConnection();
                    try (var ps = conn.prepareStatement(
                        "UPDATE outbox SET status='CLAIMED', claimed_by=? WHERE id=? AND status='NEW' " +
                        "RETURNING id, category, topic, key, type, payload, headers, attempts")) {
                        ps.setString(1, hostname);
                        ps.setObject(2, id);
                        var rs = ps.executeQuery();
                        if (rs.next()) {
                            return Optional.of(mapRow(rs));
                        }
                        return Optional.<OutboxRow>empty();
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to claim outbox entry", e);
                }
            })
            .orElse(Optional.empty());
    }

    @Override
    public List<OutboxRow> claim(int max, String claimer) {
        // Use current transaction's connection if available
        return transactionOps.findTransactionStatus()
            .map(status -> {
                try {
                    Connection conn = (Connection) status.getConnection();
                    try (var ps = conn.prepareStatement(
                        "WITH c AS (SELECT id FROM outbox WHERE status='NEW' AND (next_at IS NULL OR next_at <= NOW()) " +
                        "ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED) " +
                        "UPDATE outbox o SET status='CLAIMED', claimed_by=?, attempts=o.attempts FROM c WHERE o.id=c.id " +
                        "RETURNING o.id, o.category, o.topic, o.key, o.type, o.payload, o.headers, o.attempts")) {
                        ps.setInt(1, max);
                        ps.setString(2, claimer);
                        var rs = ps.executeQuery();
                        List<OutboxRow> result = new ArrayList<>();
                        while (rs.next()) {
                            result.add(mapRow(rs));
                        }
                        return result;
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to claim outbox batch", e);
                }
            })
            .orElseGet(ArrayList::new);
    }

    private OutboxRow mapRow(ResultSet rs) throws SQLException {
        String headersJson = rs.getString(7);
        Map<String, String> headers = headersJson != null ? Jsons.fromJson(headersJson, Map.class) : Map.of();
        return new OutboxRow(
            (UUID) rs.getObject(1),
            rs.getString(2),
            rs.getString(3),
            rs.getString(4),
            rs.getString(5),
            rs.getString(6),
            headers,
            rs.getInt(8)
        );
    }

    @Override
    public void markPublished(UUID id) {
        repository.markPublished(id);
    }

    @Override
    public void reschedule(UUID id, long backoffMs, String err) {
        repository.reschedule(id, backoffMs, err);
    }
}

package com.acme.reliable.e2e;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

final class PostgresClient implements AutoCloseable {
    private final String url;
    private final String user;
    private final String password;

    PostgresClient(java.util.Map<String, String> env) {
        String host = env.getOrDefault("POSTGRES_HOST", "localhost");
        int port = Integer.parseInt(env.getOrDefault("POSTGRES_PORT", "5432"));
        String db = env.getOrDefault("POSTGRES_DB", "reliable");
        this.user = env.getOrDefault("POSTGRES_USER", "postgres");
        this.password = env.getOrDefault("POSTGRES_PASSWORD", "postgres");
        this.url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("PostgreSQL driver not available", e);
        }
    }

    CommandRow waitForCommand(UUID id, Predicate<CommandRow> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        CommandRow last = null;
        while (Instant.now().isBefore(deadline)) {
            last = fetchCommand(id).orElse(null);
            if (last != null && condition.test(last)) {
                return last;
            }
            sleep(Duration.ofMillis(200));
        }
        throw new IllegalStateException("Command " + id + " did not satisfy condition within " + timeout + " last=" + last);
    }

    Optional<CommandRow> fetchCommand(UUID id) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    select id, status, retries, coalesce(last_error, '') as last_error, business_key
                      from command
                     where id = ?""")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new CommandRow(
                                (UUID) rs.getObject("id"),
                                rs.getString("status"),
                                rs.getInt("retries"),
                                rs.getString("last_error"),
                                rs.getString("business_key")
                        ));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    Optional<DlqEntry> fetchDlq(UUID commandId) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    select command_id, reason, error_message
                      from command_dlq
                     where command_id = ?""")) {
                ps.setObject(1, commandId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new DlqEntry(
                                (UUID) rs.getObject("command_id"),
                                rs.getString("reason"),
                                rs.getString("error_message")
                        ));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    Optional<OutboxRow> findReplyRow(UUID commandId) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    select id, topic, status, type, payload
                      from outbox
                     where category = 'reply'
                       and headers ->> 'commandId' = ?
                     order by created_at desc
                     limit 1""")) {
                ps.setString(1, commandId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapOutbox(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    Optional<OutboxRow> findEventRow(String topic, String key, String type) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    select id, topic, status, type, payload
                      from outbox
                     where category = 'event'
                       and topic = ?
                       and key = ?
                       and type = ?
                     order by created_at desc
                     limit 1""")) {
                ps.setString(1, topic);
                ps.setString(2, key);
                ps.setString(3, type);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapOutbox(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    private OutboxRow mapOutbox(ResultSet rs) throws java.sql.SQLException {
        return new OutboxRow(
                (UUID) rs.getObject("id"),
                rs.getString("topic"),
                rs.getString("status"),
                rs.getString("type"),
                rs.getString("payload")
        );
    }

    private Connection connection() throws java.sql.SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private <T> T withConnection(SqlFunction<T> callback) {
        try (Connection conn = connection()) {
            return callback.apply(conn);
        } catch (Exception e) {
            throw new IllegalStateException("Database query failed", e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        // no-op
    }

    @FunctionalInterface
    interface SqlFunction<T> {
        T apply(Connection connection) throws Exception;
    }

    record CommandRow(UUID id, String status, int retries, String lastError, String businessKey) {
    }

    record DlqEntry(UUID commandId, String reason, String errorMessage) {
    }

    record OutboxRow(UUID id, String topic, String status, String type, String payload) {
    }
}

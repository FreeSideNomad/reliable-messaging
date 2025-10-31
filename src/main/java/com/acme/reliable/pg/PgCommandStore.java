package com.acme.reliable.pg;

import com.acme.reliable.spi.CommandStore;
import io.micronaut.data.connection.ConnectionOperations;
import jakarta.inject.Singleton;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Singleton
public class PgCommandStore implements CommandStore {
    private final ConnectionOperations<Connection> connectionOps;

    public PgCommandStore(ConnectionOperations<Connection> connectionOps) {
        this.connectionOps = connectionOps;
    }

    @Override
    public UUID savePending(String name, String idem, String key, String payload, String reply) {
        return connectionOps.executeWrite(status -> {
            try {
                UUID id = UUID.randomUUID();
                try (var ps = status.getConnection().prepareStatement(
                    "insert into command(id, name, business_key, payload, idempotency_key, status, reply) values (?,?,?,?::jsonb,?,'PENDING',?::jsonb)")) {
                    ps.setObject(1, id);
                    ps.setString(2, name);
                    ps.setString(3, key);
                    ps.setString(4, payload);
                    ps.setString(5, idem);
                    ps.setString(6, reply);
                    ps.executeUpdate();
                }
                return id;
            } catch(SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public Optional<Record> find(UUID id) {
        return connectionOps.executeRead(status -> {
            try(var ps = status.getConnection().prepareStatement("select id,name,business_key,payload,status,reply from command where id=?")) {
                ps.setObject(1, id);
                var rs = ps.executeQuery();
                if(rs.next()) {
                    return Optional.of(new Record(
                        (UUID)rs.getObject(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6)
                    ));
                }
                return Optional.empty();
            } catch(SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void markRunning(UUID id, Instant lease) {
        exec("update command set status='RUNNING', processing_lease_until=? where id=?", ps -> {
            ps.setTimestamp(1, java.sql.Timestamp.from(lease));
            ps.setObject(2, id);
        });
    }

    @Override
    public void markSucceeded(UUID id) {
        exec("update command set status='SUCCEEDED', updated_at=now() where id=?", ps -> {
            ps.setObject(1, id);
        });
    }

    @Override
    public void markFailed(UUID id, String err) {
        exec("update command set status='FAILED', last_error=?, updated_at=now() where id=?", ps -> {
            ps.setString(1, err);
            ps.setObject(2, id);
        });
    }

    @Override
    public void bumpRetry(UUID id, String err) {
        exec("update command set retries=retries+1, last_error=?, updated_at=now() where id=?", ps -> {
            ps.setString(1, err);
            ps.setObject(2, id);
        });
    }

    @Override
    public void markTimedOut(UUID id, String reason) {
        exec("update command set status='TIMED_OUT', last_error=?, updated_at=now() where id=?", ps -> {
            ps.setString(1, reason);
            ps.setObject(2, id);
        });
    }

    @Override
    public boolean existsByIdempotencyKey(String k) {
        return queryOne("select 1 from command where idempotency_key=?", ps -> {
            ps.setString(1, k);
        }).isPresent();
    }

    private void exec(String sql, SqlApplier a) {
        connectionOps.executeWrite(status -> {
            try (var ps = status.getConnection().prepareStatement(sql)) {
                a.apply(ps);
                ps.executeUpdate();
                return null;
            } catch(SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Optional<Integer> queryOne(String sql, SqlApplier a) {
        return connectionOps.executeRead(status -> {
            try (var ps = status.getConnection().prepareStatement(sql)) {
                a.apply(ps);
                var rs = ps.executeQuery();
                return rs.next() ? Optional.of(1) : Optional.empty();
            } catch(SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    interface SqlApplier {
        void apply(PreparedStatement ps) throws SQLException;
    }
}

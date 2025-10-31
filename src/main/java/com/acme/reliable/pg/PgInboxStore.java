package com.acme.reliable.pg;

import com.acme.reliable.spi.InboxStore;
import io.micronaut.data.connection.ConnectionOperations;
import jakarta.inject.Singleton;
import java.sql.*;

@Singleton
public class PgInboxStore implements InboxStore {
    private final ConnectionOperations<Connection> connectionOps;

    public PgInboxStore(ConnectionOperations<Connection> connectionOps) {
        this.connectionOps = connectionOps;
    }

    @Override
    public boolean markIfAbsent(String messageId, String handler) {
        return connectionOps.executeWrite(status -> {
            try (var ps = status.getConnection().prepareStatement("insert into inbox(message_id, handler) values(?,?) on conflict do nothing")) {
                ps.setString(1, messageId);
                ps.setString(2, handler);
                return ps.executeUpdate() == 1;
            } catch(SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}

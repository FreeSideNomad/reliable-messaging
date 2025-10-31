package com.acme.reliable.pg;

import com.acme.reliable.spi.OutboxStore;
import io.micronaut.data.connection.ConnectionOperations;
import jakarta.inject.Singleton;
import java.sql.*;
import java.util.*;

@Singleton
public class PgOutboxStore implements OutboxStore {
    private final ConnectionOperations<Connection> connectionOps;

    public PgOutboxStore(ConnectionOperations<Connection> connectionOps) {
        this.connectionOps = connectionOps;
    }

    @Override
    public UUID addReturningId(OutboxRow r) {
        return connectionOps.executeWrite(status -> {
            try {
                var id = r.id() != null ? r.id() : UUID.randomUUID();
                try (var ps = status.getConnection().prepareStatement(
                    "insert into outbox(id,category,topic,key,type,payload,headers,status,attempts) values (?,?,?,?,?,?::jsonb,?::jsonb,'NEW',0)")) {
                    ps.setObject(1, id);
                    ps.setString(2, r.category());
                    ps.setString(3, r.topic());
                    ps.setString(4, r.key());
                    ps.setString(5, r.type());
                    ps.setString(6, r.payload());
                    ps.setString(7, com.acme.reliable.core.Jsons.toJson(r.headers()));
                    ps.executeUpdate();
                }
                return id;
            } catch(SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public Optional<OutboxRow> claimOne(UUID id) {
        return connectionOps.executeWrite(status -> {
            try (var ps = status.getConnection().prepareStatement(
                "update outbox set status='CLAIMED', claimed_by=? where id=? and status='NEW' returning id,category,topic,key,type,payload,headers,attempts")) {
                ps.setString(1, java.net.InetAddress.getLoopbackAddress().getHostName());
                ps.setObject(2, id);
                var rs = ps.executeQuery();
                if (rs.next()) {
                    String headersJson = rs.getString(7);
                    Map<String,String> headers = headersJson != null ? com.acme.reliable.core.Jsons.fromJson(headersJson, Map.class) : Map.of();
                    var row = new OutboxRow(
                        (UUID)rs.getObject(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        headers,
                        rs.getInt(8)
                    );
                    return Optional.of(row);
                }
                return Optional.empty();
            } catch(SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public List<OutboxRow> claim(int max, String claimer) {
        return connectionOps.executeWrite(status -> {
            try {
                var ps = status.getConnection().prepareStatement(
                    "with c as (select id from outbox where status='NEW' and (next_at is null or next_at<=now()) order by created_at limit ? for update skip locked) " +
                    "update outbox o set status='CLAIMED', claimed_by=?, attempts=o.attempts from c where o.id=c.id returning o.id,o.category,o.topic,o.key,o.type,o.payload,o.headers,o.attempts");
                ps.setInt(1, max);
                ps.setString(2, claimer);
                var rs = ps.executeQuery();
                var out = new ArrayList<OutboxRow>();
                while(rs.next()) {
                    String headersJson = rs.getString(7);
                    Map<String,String> headers = headersJson != null ? com.acme.reliable.core.Jsons.fromJson(headersJson, Map.class) : Map.of();
                    out.add(new OutboxRow(
                        (UUID)rs.getObject(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        headers,
                        rs.getInt(8)
                    ));
                }
                return out;
            } catch(SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void markPublished(UUID id) {
        exec("update outbox set status='PUBLISHED', published_at=now() where id=?", ps -> {
            ps.setObject(1, id);
        });
    }

    @Override
    public void reschedule(UUID id, long backoffMs, String err) {
        exec("update outbox set status='NEW', next_at=now()+ (?||' milliseconds')::interval, attempts=attempts+1, last_error=? where id=?", ps -> {
            ps.setLong(1, backoffMs);
            ps.setString(2, err);
            ps.setObject(3, id);
        });
    }

    private void exec(String sql, PgCommandStore.SqlApplier a) {
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
}

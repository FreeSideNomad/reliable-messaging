package com.acme.reliable.pg;

import com.acme.reliable.spi.DlqStore;
import jakarta.inject.Singleton;
import javax.sql.DataSource;

@Singleton
public class PgDlqStore implements DlqStore {
    private final DataSource ds;

    public PgDlqStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void park(java.util.UUID commandId, String commandName, String businessKey, String payload,
                     String failedStatus, String errorClass, String errorMessage, int attempts, String parkedBy) {
        try (var c = ds.getConnection();
             var ps = c.prepareStatement(
                "insert into command_dlq(command_id, command_name, business_key, payload, failed_status, error_class, error_message, attempts, parked_by) " +
                "values (?,?,?,?::jsonb,?,?,?,?,?)")) {
            ps.setObject(1, commandId);
            ps.setString(2, commandName);
            ps.setString(3, businessKey);
            ps.setString(4, payload);
            ps.setString(5, failedStatus);
            ps.setString(6, errorClass);
            ps.setString(7, errorMessage);
            ps.setInt(8, attempts);
            ps.setString(9, parkedBy);
            ps.executeUpdate();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}

package com.acme.reliable.integration;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

@Requires(env = "disabled")
@Singleton
public class IntegrationTestBase implements ApplicationEventListener<StartupEvent> {

    @Inject
    javax.sql.DataSource dataSource;

    private static boolean schemaInitialized = false;

    @Override
    public void onApplicationEvent(StartupEvent event) {
        if (!schemaInitialized) {
            try {
                String sql;
                try (var reader = new BufferedReader(
                    new InputStreamReader(
                        getClass().getClassLoader().getResourceAsStream("schema.sql")
                    ))) {
                    sql = reader.lines().collect(Collectors.joining("\n"));
                }

                try (var conn = dataSource.getConnection();
                     var stmt = conn.createStatement()) {
                    stmt.execute(sql);
                }

                schemaInitialized = true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize test schema", e);
            }
        }
    }
}

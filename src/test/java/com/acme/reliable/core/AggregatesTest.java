package com.acme.reliable.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AggregatesTest {

    @Test
    void testSnapshot() {
        String snapshot = Aggregates.snapshot("user-123");
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("user-123"));
        assertTrue(snapshot.contains("aggregateKey"));
        assertTrue(snapshot.contains("version"));
    }
}

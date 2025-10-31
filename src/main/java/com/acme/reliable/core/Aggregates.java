package com.acme.reliable.core;

/**
 * Helper class to build aggregate snapshots for event publishing
 */
public final class Aggregates {

    public static String snapshot(String key) {
        // In production, this would fetch the current aggregate state
        // For now, return a simple snapshot JSON
        return String.format("{\"aggregateKey\":\"%s\",\"version\":1}", key);
    }
}

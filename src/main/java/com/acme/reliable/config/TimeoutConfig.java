package com.acme.reliable.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.time.Duration;

/**
 * Configuration for various timeout and retry settings.
 */
@ConfigurationProperties("timeout")
public class TimeoutConfig {

    private Duration commandLease = Duration.ofMinutes(5);
    private Duration maxBackoff = Duration.ofMinutes(5);
    private Duration syncWait = Duration.ofSeconds(1);

    public Duration getCommandLease() {
        return commandLease;
    }

    public void setCommandLease(Duration commandLease) {
        this.commandLease = commandLease;
    }

    public long getCommandLeaseSeconds() {
        return commandLease.toSeconds();
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public long getMaxBackoffMillis() {
        return maxBackoff.toMillis();
    }

    public Duration getSyncWait() {
        return syncWait;
    }

    public void setSyncWait(Duration syncWait) {
        this.syncWait = syncWait;
    }

    public long getSyncWaitMillis() {
        return syncWait.toMillis();
    }

    public boolean isAsync() {
        return syncWait.isZero();
    }
}

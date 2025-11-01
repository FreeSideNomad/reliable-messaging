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
}

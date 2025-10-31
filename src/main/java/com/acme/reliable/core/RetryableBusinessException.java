package com.acme.reliable.core;

public class RetryableBusinessException extends RuntimeException {
    public RetryableBusinessException(String message) {
        super(message);
    }
}

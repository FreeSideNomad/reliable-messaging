package com.acme.reliable.spi;

public interface InboxStore {
    boolean markIfAbsent(String messageId, String handler);
}

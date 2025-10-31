package com.acme.reliable.spi;

import java.util.Map;

public interface CommandQueue {
    void send(String queue, String body, Map<String,String> headers);
}

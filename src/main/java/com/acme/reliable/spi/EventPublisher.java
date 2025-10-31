package com.acme.reliable.spi;

import java.util.Map;

public interface EventPublisher {
    void publish(String topic, String key, String value, Map<String,String> headers);
}

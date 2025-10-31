package com.acme.reliable.test;

import com.acme.reliable.spi.EventPublisher;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Singleton
@Requires(env = Environment.TEST)
@Requires(missingBeans = com.acme.reliable.kafka.MnKafkaPublisher.class)
public class MockEventPublisher implements EventPublisher {

    public static class PublishedEvent {
        public final String topic;
        public final String key;
        public final String value;
        public final Map<String, String> headers;

        public PublishedEvent(String topic, String key, String value, Map<String, String> headers) {
            this.topic = topic;
            this.key = key;
            this.value = value;
            this.headers = headers;
        }
    }

    private final List<PublishedEvent> publishedEvents = new ArrayList<>();

    @Override
    public void publish(String topic, String key, String value, Map<String, String> headers) {
        publishedEvents.add(new PublishedEvent(topic, key, value, headers));
    }

    public List<PublishedEvent> getPublishedEvents() {
        return publishedEvents;
    }

    public void clear() {
        publishedEvents.clear();
    }
}

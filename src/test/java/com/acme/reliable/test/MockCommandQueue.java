package com.acme.reliable.test;

import com.acme.reliable.spi.CommandQueue;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Singleton
@Requires(env = Environment.TEST)
@Requires(missingBeans = com.acme.reliable.mq.JmsCommandQueue.class)
public class MockCommandQueue implements CommandQueue {

    public static class SentMessage {
        public final String queue;
        public final String body;
        public final Map<String, String> headers;

        public SentMessage(String queue, String body, Map<String, String> headers) {
            this.queue = queue;
            this.body = body;
            this.headers = headers;
        }
    }

    private final List<SentMessage> sentMessages = new ArrayList<>();

    @Override
    public void send(String queue, String body, Map<String, String> headers) {
        sentMessages.add(new SentMessage(queue, body, headers));
    }

    public List<SentMessage> getSentMessages() {
        return sentMessages;
    }

    public void clear() {
        sentMessages.clear();
    }
}

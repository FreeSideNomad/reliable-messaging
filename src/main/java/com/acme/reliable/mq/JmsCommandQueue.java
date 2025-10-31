package com.acme.reliable.mq;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.annotation.PreDestroy;

@Singleton
@Requires(beans = IbmMqFactoryProvider.class)
public class JmsCommandQueue implements com.acme.reliable.spi.CommandQueue {
    private final jakarta.jms.Connection connection;

    public JmsCommandQueue(@Named("mqConnectionFactory") jakarta.jms.ConnectionFactory cf) {
        try {
            this.connection = cf.createConnection();
            this.connection.start();
        } catch (jakarta.jms.JMSException e) {
            throw new RuntimeException("Failed to initialize JMS connection", e);
        }
    }

    @Override
    public void send(String queue, String body, java.util.Map<String,String> headers) {
        jakarta.jms.Session session = null;
        try {
            session = connection.createSession(true, jakarta.jms.Session.SESSION_TRANSACTED);
            var dest = session.createQueue(queue);
            var msg = session.createTextMessage(body);
            if (headers != null) {
                if (headers.containsKey("correlationId")) {
                    msg.setJMSCorrelationID(headers.get("correlationId"));
                }
                if (headers.containsKey("replyTo")) {
                    msg.setJMSReplyTo(session.createQueue(headers.get("replyTo")));
                }
                for (var e : headers.entrySet()) {
                    String key = e.getKey();
                    // Skip special JMS headers and IBM MQ internal properties
                    if (key.equals("correlationId") || key.equals("replyTo") || key.equals("mode") ||
                        key.startsWith("JMS_IBM_") || key.startsWith("JMSX")) {
                        continue;
                    }
                    msg.setStringProperty(key, e.getValue());
                }
            }
            try (var producer = session.createProducer(dest)) {
                producer.send(msg);
            }
            session.commit();
        } catch(Exception e) {
            if (session != null) {
                try {
                    session.rollback();
                } catch (jakarta.jms.JMSException ignored) {
                }
            }
            throw new RuntimeException(e);
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (jakarta.jms.JMSException ignored) {
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }
}

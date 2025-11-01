package com.acme.reliable.mq;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Requires(beans = IbmMqFactoryProvider.class)
public class JmsCommandQueue implements com.acme.reliable.spi.CommandQueue {
    private static final Logger LOG = LoggerFactory.getLogger(JmsCommandQueue.class);

    private final jakarta.jms.Connection connection;
    private final ThreadLocal<SessionHolder> sessionPool;

    public JmsCommandQueue(@Named("mqConnectionFactory") jakarta.jms.ConnectionFactory cf) {
        try {
            this.connection = cf.createConnection();
            this.connection.start();
            LOG.info("JMS connection initialized and started");
        } catch (jakarta.jms.JMSException e) {
            throw new RuntimeException("Failed to initialize JMS connection", e);
        }

        // Initialize ThreadLocal after connection is established
        this.sessionPool = ThreadLocal.withInitial(() -> {
            try {
                LOG.debug("Creating new JMS session for thread {}", Thread.currentThread().getName());
                return new SessionHolder(connection.createSession(true, jakarta.jms.Session.SESSION_TRANSACTED));
            } catch (jakarta.jms.JMSException e) {
                throw new RuntimeException("Failed to create JMS session", e);
            }
        });
    }

    @Override
    public void send(String queue, String body, java.util.Map<String,String> headers) {
        SessionHolder holder = sessionPool.get();

        try {
            var session = holder.session;
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

            // Reuse the pooled producer
            holder.producer.send(dest, msg);
            session.commit();

        } catch(Exception e) {
            try {
                holder.session.rollback();
            } catch (jakarta.jms.JMSException rollbackEx) {
                LOG.warn("Failed to rollback JMS session", rollbackEx);
            }

            // On error, invalidate the session and create a new one
            holder.close();
            sessionPool.remove();

            throw new RuntimeException("Failed to send message to queue: " + queue, e);
        }
    }

    @PreDestroy
    void shutdown() {
        LOG.info("Shutting down JMS connection pool");
        try {
            // Clean up thread-local sessions
            sessionPool.remove();
            connection.close();
        } catch (Exception e) {
            LOG.warn("Error during JMS shutdown", e);
        }
    }

    /**
     * Holds a JMS session and producer per thread for reuse.
     * This dramatically improves performance by avoiding session/producer creation overhead.
     */
    private static class SessionHolder {
        final jakarta.jms.Session session;
        final jakarta.jms.MessageProducer producer;

        SessionHolder(jakarta.jms.Session session) throws jakarta.jms.JMSException {
            this.session = session;
            // Create a generic producer (destination set per send call)
            this.producer = session.createProducer(null);
        }

        void close() {
            try {
                if (producer != null) {
                    producer.close();
                }
            } catch (jakarta.jms.JMSException e) {
                LOG.debug("Error closing producer", e);
            }
            try {
                if (session != null) {
                    session.close();
                }
            } catch (jakarta.jms.JMSException e) {
                LOG.debug("Error closing session", e);
            }
        }
    }
}

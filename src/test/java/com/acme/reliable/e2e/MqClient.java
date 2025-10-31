package com.acme.reliable.e2e;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;

final class MqClient implements AutoCloseable {
    private final Connection connection;

    MqClient(Map<String, String> env) {
        try {
            MQConnectionFactory factory = new MQConnectionFactory();
            factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
            factory.setHostName(env.getOrDefault("MQ_HOST", "localhost"));
            factory.setPort(Integer.parseInt(env.getOrDefault("MQ_PORT", "1414")));
            factory.setQueueManager(env.getOrDefault("MQ_QMGR", "QM1"));
            factory.setChannel(env.getOrDefault("MQ_CHANNEL", "DEV.APP.SVRCONN"));
            factory.setAppName("reliable-e2e");
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            factory.setStringProperty(WMQConstants.USERID, env.getOrDefault("MQ_USER", "app"));
            factory.setStringProperty(WMQConstants.PASSWORD, env.getOrDefault("MQ_PASS", "passw0rd"));
            this.connection = factory.createConnection();
            this.connection.start();
        } catch (JMSException e) {
            throw new IllegalStateException("Unable to open MQ connection", e);
        }
    }

    Optional<String> browseTextMessage(String queueName, String correlationId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            Queue queue = session.createQueue(queueName);
            while (Instant.now().isBefore(deadline)) {
                try (QueueBrowser browser = session.createBrowser(queue)) {
                    Enumeration<?> enumeration = browser.getEnumeration();
                    while (enumeration.hasMoreElements()) {
                        jakarta.jms.Message message = (jakarta.jms.Message) enumeration.nextElement();
                        if (correlationMatches(message, correlationId) && message instanceof TextMessage text) {
                            return Optional.ofNullable(text.getText());
                        }
                    }
                }
                sleep(Duration.ofMillis(200));
            }
            return Optional.empty();
        } catch (JMSException e) {
            throw new IllegalStateException("Failed to browse queue " + queueName, e);
        }
    }

    private boolean correlationMatches(jakarta.jms.Message message, String correlationId) throws JMSException {
        if (correlationId == null) {
            return true;
        }
        if (correlationId.equals(message.getJMSCorrelationID())) {
            return true;
        }
        if (message.propertyExists("commandId")) {
            return correlationId.equals(message.getStringProperty("commandId"));
        }
        return false;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (JMSException ignored) {
        }
    }
}

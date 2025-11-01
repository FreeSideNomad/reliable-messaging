package com.acme.reliable.mq;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.jms.pool.JMSConnectionPool;
import jakarta.inject.Singleton;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;

/**
 * Initializes required IBM MQ queues when the connection pool is created.
 * If queues don't exist and cannot be created, the application will exit.
 * This runs before JMS listeners are registered to ensure queues exist.
 */
@Singleton
@Requires(beans = IbmMqFactoryProvider.class)
@Requires(property = "jms.consumers.enabled", value = "true", defaultValue = "false")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MqQueueInitializer implements BeanCreatedEventListener<JMSConnectionPool> {

    private static final Logger LOG = LoggerFactory.getLogger(MqQueueInitializer.class);
    private static final int MQ_ERROR_UNKNOWN_OBJECT = 2085;

    private final String[] requiredQueues;
    private boolean validated = false;

    public MqQueueInitializer(
            @io.micronaut.context.annotation.Value("${mq.required-queues:APP.CMD.CreateUser.Q,APP.CMD.REPLY.Q}") String requiredQueuesConfig) {
        this.requiredQueues = requiredQueuesConfig.split(",");
    }

    @Override
    public JMSConnectionPool onCreated(BeanCreatedEvent<JMSConnectionPool> event) {
        if (!validated) {
            validated = true;
            JMSConnectionPool pool = event.getBean();
            LOG.info("Validating IBM MQ queues...");

            try (var connection = pool.createConnection();
                 var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {

                for (String queueName : requiredQueues) {
                    try {
                        // Try to access the queue
                        var queue = session.createQueue(queueName);
                        var browser = session.createBrowser(queue);
                        browser.close();
                        LOG.info("Queue {} exists and is accessible", queueName);
                    } catch (JMSException e) {
                        // Check if the error is due to missing queue
                        if (isMissingQueueError(e)) {
                            LOG.warn("Queue {} does not exist.", queueName);
                            createQueue(connection, queueName);
                        } else {
                            throw e;
                        }
                    }
                }

                LOG.info("IBM MQ queue validation completed successfully");

            } catch (JMSException e) {
                LOG.error("Failed to validate IBM MQ queues", e);
                exitApplication("Cannot validate/access IBM MQ queues: " + e.getMessage());
            } catch (Exception e) {
                LOG.error("Failed to initialize IBM MQ queues", e);
                exitApplication("Cannot initialize IBM MQ queues: " + e.getMessage());
            }
        }

        return event.getBean();
    }

    private boolean isMissingQueueError(JMSException e) {
        Throwable cause = e.getCause();
        if (cause instanceof MQException) {
            MQException mqe = (MQException) cause;
            return mqe.getReason() == MQConstants.MQRC_UNKNOWN_OBJECT_NAME;
        }
        return e.getMessage() != null &&
               (e.getMessage().contains("MQRC_UNKNOWN_OBJECT_NAME") ||
                e.getMessage().contains(String.valueOf(MQ_ERROR_UNKNOWN_OBJECT)));
    }

    private void createQueue(jakarta.jms.Connection connection, String queueName) throws Exception {
        // Queue doesn't exist - provide helpful error message
        LOG.error("CRITICAL: Queue {} does not exist.", queueName);
        LOG.error("Please create the queue manually using:");
        LOG.error("  docker exec -i reliable-ibmmq /opt/mqm/bin/runmqsc QM1 <<EOF");
        LOG.error("  DEFINE QLOCAL('{}') DEFPSIST(YES) REPLACE", queueName);
        LOG.error("  EOF");
        throw new IllegalStateException("Queue " + queueName + " does not exist. Manual creation required.");
    }

    private void exitApplication(String reason) {
        LOG.error("CRITICAL ERROR: {}", reason);
        LOG.error("Application cannot start. Exiting...");
        System.exit(1);
    }
}

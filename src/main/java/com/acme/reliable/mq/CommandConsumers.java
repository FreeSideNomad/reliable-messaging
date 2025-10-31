package com.acme.reliable.mq;

import io.micronaut.context.annotation.Requires;
import io.micronaut.jms.annotations.JMSListener;
import io.micronaut.jms.annotations.Queue;
import io.micronaut.jms.annotations.Message;
import io.micronaut.messaging.annotation.MessageBody;

@Requires(beans = IbmMqFactoryProvider.class)
@Requires(property = "jms.consumers.enabled", value = "true", defaultValue = "false")
@JMSListener("mqConnectionFactory")
public class CommandConsumers {
    private final com.acme.reliable.core.Executor exec;
    private final com.acme.reliable.core.ResponseRegistry responses;

    public CommandConsumers(com.acme.reliable.core.Executor e, com.acme.reliable.core.ResponseRegistry r) {
        this.exec = e;
        this.responses = r;
    }

    @Queue("APP.CMD.CreateUser.Q")
    public void onCreateUser(@MessageBody String body, @Message jakarta.jms.Message m) throws jakarta.jms.JMSException {
        var env = Mappers.toEnvelope(body, m);
        exec.process(env);
    }

    @Queue("APP.CMD.REPLY.Q")
    public void onReply(@MessageBody String body, @Message jakarta.jms.Message m) throws jakarta.jms.JMSException {
        var commandId = m.getStringProperty("commandId");
        if (commandId != null) {
            responses.complete(java.util.UUID.fromString(commandId), body);
        }
    }
}

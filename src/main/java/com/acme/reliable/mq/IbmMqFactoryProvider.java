package com.acme.reliable.mq;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.jms.annotations.JMSConnectionFactory;

@Requires(notEnv = "test")
@Factory
public class IbmMqFactoryProvider {

    @JMSConnectionFactory("mqConnectionFactory")
    public jakarta.jms.ConnectionFactory mqConnectionFactory() throws Exception {
        var cf = new com.ibm.mq.jakarta.jms.MQConnectionFactory();
        cf.setTransportType(com.ibm.msg.client.jakarta.wmq.WMQConstants.WMQ_CM_CLIENT);
        cf.setHostName(System.getenv().getOrDefault("MQ_HOST", "localhost"));
        cf.setPort(Integer.parseInt(System.getenv().getOrDefault("MQ_PORT", "1414")));
        cf.setQueueManager(System.getenv().getOrDefault("MQ_QMGR", "QM1"));
        cf.setChannel(System.getenv().getOrDefault("MQ_CHANNEL", "DEV.APP.SVRCONN"));
        cf.setAppName("reliable-cmd");
        cf.setBooleanProperty(com.ibm.msg.client.jakarta.wmq.WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        cf.setStringProperty(com.ibm.msg.client.jakarta.wmq.WMQConstants.USERID, System.getenv().getOrDefault("MQ_USER", "app"));
        cf.setStringProperty(com.ibm.msg.client.jakarta.wmq.WMQConstants.PASSWORD, System.getenv().getOrDefault("MQ_PASS", "passw0rd"));
        return cf;
    }
}

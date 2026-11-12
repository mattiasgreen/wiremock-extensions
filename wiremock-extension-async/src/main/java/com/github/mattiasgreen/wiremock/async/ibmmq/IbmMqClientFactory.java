package com.github.mattiasgreen.wiremock.async.ibmmq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import java.util.Hashtable;
import javax.jms.JMSContext;
import javax.jms.JMSException;

/**
 * Factory creating authenticated IBM MQ JMS contexts and PCF management agents
 * from an {@link IbmMqConnectionConfig}.
 */
final class IbmMqClientFactory {

    private IbmMqClientFactory() {}

    static MQConnectionFactory createConnectionFactory(IbmMqConnectionConfig config) throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setHostName(config.host());
        factory.setPort(config.port());
        factory.setQueueManager(config.queueManager());
        factory.setChannel(config.channel());
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setCCSID(config.ccsid());

        if (config.username() != null && config.password() != null) {
            factory.setStringProperty(WMQConstants.USERID, config.username());
            factory.setStringProperty(WMQConstants.PASSWORD, config.password());
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        }
        if (config.sslCipherSuite() != null) {
            factory.setSSLCipherSuite(config.sslCipherSuite());
        }
        if (config.sslPeerName() != null) {
            factory.setSSLPeerName(config.sslPeerName());
        }
        return factory;
    }

    static JMSContext openJmsContext(IbmMqConnectionConfig config) throws JMSException {
        MQConnectionFactory factory = createConnectionFactory(config);
        if (config.username() != null && config.password() != null) {
            return factory.createContext(config.username(), config.password(), JMSContext.AUTO_ACKNOWLEDGE);
        }
        return factory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
    }

    static MQQueueManager openQueueManager(IbmMqConnectionConfig config) throws MQException {
        Hashtable<String, Object> props = new Hashtable<>();
        props.put(CMQC.HOST_NAME_PROPERTY, config.host());
        props.put(CMQC.PORT_PROPERTY, config.port());
        props.put(CMQC.CHANNEL_PROPERTY, config.channel());
        props.put(CMQC.TRANSPORT_PROPERTY, CMQC.TRANSPORT_MQSERIES_CLIENT);
        props.put(CMQC.CCSID_PROPERTY, config.ccsid());

        if (config.username() != null && config.password() != null) {
            props.put(CMQC.USER_ID_PROPERTY, config.username());
            props.put(CMQC.PASSWORD_PROPERTY, config.password());
            props.put(CMQC.USE_MQCSP_AUTHENTICATION_PROPERTY, Boolean.TRUE);
        }
        if (config.sslCipherSuite() != null) {
            props.put(CMQC.SSL_CIPHER_SUITE_PROPERTY, config.sslCipherSuite());
        }
        if (config.sslPeerName() != null) {
            props.put(CMQC.SSL_PEER_NAME_PROPERTY, config.sslPeerName());
        }
        return new MQQueueManager(config.queueManager(), props);
    }

    static PCFMessageAgent openPcfAgent(MQQueueManager queueManager) throws MQDataException {
        return new PCFMessageAgent(queueManager);
    }
}

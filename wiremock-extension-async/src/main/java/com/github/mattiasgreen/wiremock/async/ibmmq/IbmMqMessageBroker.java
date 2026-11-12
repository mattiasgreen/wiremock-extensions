package com.github.mattiasgreen.wiremock.async.ibmmq;

import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.CMQCFC;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import com.ibm.mq.jms.MQConnectionFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import javax.jms.JMSConsumer;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.JMSRuntimeException;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IBM MQ {@link MessageBroker} bridging WireMock async channels to a live IBM MQ Queue Manager
 * over native {@code SVRCONN} TCP channels using JMS 2.0 and PCF management queries.
 *
 * <p><b>Note:</b> Beta functionality subject to evolution.
 */
public class IbmMqMessageBroker implements MessageBroker, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IbmMqMessageBroker.class);

    private final IbmMqConnectionConfig config;
    private volatile MQConnectionFactory cachedFactory;

    public IbmMqMessageBroker(BrokerConnection connection) {
        this(IbmMqConnectionConfig.from(connection));
    }

    public IbmMqMessageBroker(IbmMqConnectionConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public IbmMqConnectionConfig getConfig() {
        return config;
    }

    @Override
    public void send(String channel, AsyncMessage message) {
        if (channel == null || channel.isBlank() || message == null) {
            return;
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(channel);
        try (JMSContext context = openContext()) {
            Queue destination = context.createQueue(normalizedQueue);
            TextMessage jmsMsg = IbmMqMessageMapper.toJmsTextMessage(context, message);
            context.createProducer().send(destination, jmsMsg);
        } catch (JMSException | JMSRuntimeException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.error("[IBMMQ-BROKER] Failed to send message to queue [{}]: {}", safeDest, safeErr);
            throw new IllegalStateException("Failed to send message to IBM MQ queue: " + safeDest, e);
        }
    }

    @Override
    public AsyncMessage poll(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(channel);
        try (JMSContext context = openContext()) {
            Queue destination = context.createQueue(normalizedQueue);
            try (JMSConsumer consumer = context.createConsumer(destination)) {
                Message received = consumer.receiveNoWait();
                if (received == null) {
                    return null;
                }
                return IbmMqMessageMapper.toAsyncMessage(normalizedQueue, received);
            }
        } catch (JMSException | JMSRuntimeException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.warn("[IBMMQ-BROKER] Poll error on queue [{}]: {}", safeDest, safeErr);
            return null;
        }
    }

    @Override
    public int size(String channel) {
        if (channel == null || channel.isBlank()) {
            return 0;
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(channel);
        MQQueueManager qmgr = null;
        PCFMessageAgent agent = null;
        try {
            qmgr = IbmMqClientFactory.openQueueManager(config);
            agent = IbmMqClientFactory.openPcfAgent(qmgr);
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_Q);
            request.addParameter(CMQC.MQCA_Q_NAME, normalizedQueue);
            request.addParameter(CMQC.MQIA_Q_TYPE, CMQC.MQQT_LOCAL);
            request.addParameter(CMQCFC.MQIACF_Q_ATTRS, new int[] {CMQC.MQIA_CURRENT_Q_DEPTH});

            PCFMessage[] responses = agent.send(request);
            if (responses != null && responses.length > 0) {
                return responses[0].getIntParameterValue(CMQC.MQIA_CURRENT_Q_DEPTH);
            }
        } catch (MQException | MQDataException | IOException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.debug("[IBMMQ-BROKER] PCF size fallback to QueueBrowser for [{}]: {}", safeDest, safeErr);
        } finally {
            closeQuietly(agent, qmgr);
        }
        return peek(normalizedQueue).size();
    }

    @Override
    public List<String> listChannels() {
        List<String> discovered = new ArrayList<>();
        MQQueueManager qmgr = null;
        PCFMessageAgent agent = null;
        try {
            qmgr = IbmMqClientFactory.openQueueManager(config);
            agent = IbmMqClientFactory.openPcfAgent(qmgr);
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_Q_NAMES);
            request.addParameter(CMQC.MQCA_Q_NAME, "*");
            request.addParameter(CMQC.MQIA_Q_TYPE, CMQC.MQQT_LOCAL);

            PCFMessage[] responses = agent.send(request);
            if (responses != null && responses.length > 0) {
                String[] names = responses[0].getStringListParameterValue(CMQCFC.MQCACF_Q_NAMES);
                if (names != null) {
                    for (String q : names) {
                        String trimmed = q != null ? q.trim() : "";
                        if (!trimmed.isEmpty()
                                && (config.includeSystemQueues() || !IbmMqConnectionConfig.isSystemQueue(trimmed))) {
                            discovered.add(trimmed);
                        }
                    }
                }
            }
            return Collections.unmodifiableList(discovered);
        } catch (MQException | MQDataException | IOException e) {
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.debug("[IBMMQ-BROKER] PCF queue inquiry unavailable, using configured fallback: {}", safeErr);
            return config.configuredQueues();
        } finally {
            closeQuietly(agent, qmgr);
        }
    }

    @Override
    public List<AsyncMessage> peek(String channel) {
        if (channel == null || channel.isBlank()) {
            return Collections.emptyList();
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(channel);
        List<AsyncMessage> messages = new ArrayList<>();
        try (JMSContext context = openContext()) {
            Queue destination = context.createQueue(normalizedQueue);
            try (QueueBrowser browser = context.createBrowser(destination)) {
                Enumeration<?> enumeration = browser.getEnumeration();
                while (enumeration != null && enumeration.hasMoreElements()) {
                    Object next = enumeration.nextElement();
                    if (next instanceof Message jmsMsg) {
                        messages.add(IbmMqMessageMapper.toAsyncMessage(normalizedQueue, jmsMsg));
                    }
                }
            }
        } catch (JMSException | JMSRuntimeException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.warn("[IBMMQ-BROKER] Failed to peek queue [{}]: {}", safeDest, safeErr);
        }
        return Collections.unmodifiableList(messages);
    }

    @Override
    public void close() {
        cachedFactory = null;
    }

    private JMSContext openContext() throws JMSException {
        MQConnectionFactory factory = cachedFactory;
        if (factory == null) {
            factory = IbmMqClientFactory.createConnectionFactory(config);
            cachedFactory = factory;
        }
        if (config.username() != null && config.password() != null) {
            return factory.createContext(config.username(), config.password(), JMSContext.AUTO_ACKNOWLEDGE);
        }
        return factory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
    }

    static void closeQuietly(PCFMessageAgent agent, MQQueueManager qmgr) {
        if (agent != null) {
            try {
                agent.disconnect();
            } catch (MQDataException ignored) {
                // ignore disconnect cleanup failure
            }
        }
        if (qmgr != null) {
            try {
                qmgr.disconnect();
            } catch (MQException ignored) {
                // ignore disconnect cleanup failure
            }
        }
    }

    static boolean isNotAuthorized(Throwable err) {
        return err instanceof PCFException pcf && pcf.reasonCode == CMQC.MQRC_NOT_AUTHORIZED;
    }
}

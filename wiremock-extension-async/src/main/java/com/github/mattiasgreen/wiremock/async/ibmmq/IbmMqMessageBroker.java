package com.github.mattiasgreen.wiremock.async.ibmmq;

import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int MAX_DRAIN_SAFETY = 10_000;

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
        return peek(channel, 0);
    }

    @Override
    public List<AsyncMessage> peek(String channel, int limit) {
        if (channel == null || channel.isBlank()) {
            return Collections.emptyList();
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(channel);
        List<AsyncMessage> result = new ArrayList<>();
        try (JMSContext context = openContext()) {
            Queue queue = context.createQueue(normalizedQueue);
            try (QueueBrowser browser = context.createBrowser(queue)) {
                Enumeration<?> enumeration = browser.getEnumeration();
                int count = 0;
                while (enumeration != null && enumeration.hasMoreElements()) {
                    if (limit > 0 && count >= limit) {
                        break;
                    }
                    Object next = enumeration.nextElement();
                    if (next instanceof Message msg) {
                        result.add(IbmMqMessageMapper.toAsyncMessage(normalizedQueue, msg));
                        count++;
                    }
                }
            }
        } catch (JMSException | JMSRuntimeException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.warn("[IBMMQ-BROKER] Failed to browse queue [{}]: {}", safeDest, safeErr);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean delete(String channel, String messageId) {
        if (channel == null || channel.isBlank() || messageId == null || messageId.isBlank()) {
            return false;
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(channel);
        String selector = "JMSMessageID = '" + IbmMqMessageMapper.escapeSelectorLiteral(messageId) + "'";
        try (JMSContext context = openContext()) {
            Queue queue = context.createQueue(normalizedQueue);
            try (JMSConsumer consumer = context.createConsumer(queue, selector)) {
                return consumer.receiveNoWait() != null;
            }
        } catch (JMSException | JMSRuntimeException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.warn("[IBMMQ-BROKER] Failed to delete message on queue [{}]: {}", safeDest, safeErr);
            return false;
        }
    }

    @Override
    public void purge(String channel) {
        if (channel == null || channel.isBlank()) {
            return;
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(channel);
        if (tryClearViaPcf(normalizedQueue)) {
            return;
        }
        try (JMSContext context = openContext()) {
            Queue queue = context.createQueue(normalizedQueue);
            try (JMSConsumer consumer = context.createConsumer(queue)) {
                int drained = 0;
                while (drained < MAX_DRAIN_SAFETY && consumer.receiveNoWait() != null) {
                    drained++;
                }
            }
        } catch (JMSException | JMSRuntimeException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.warn("[IBMMQ-BROKER] Failed to purge queue [{}]: {}", safeDest, safeErr);
        }
    }

    @Override
    public boolean ownsDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return false;
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(destination);
        return config.configuredQueues().contains(normalizedQueue);
    }

    @Override
    public List<AsyncDestination> discoverDestinations() {
        List<AsyncDestination> result = new ArrayList<>();
        MQQueueManager qmgr = null;
        PCFMessageAgent agent = null;
        try {
            qmgr = IbmMqClientFactory.openQueueManager(config);
            agent = IbmMqClientFactory.openPcfAgent(qmgr);
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_Q);
            request.addParameter(CMQC.MQCA_Q_NAME, "*");
            request.addParameter(CMQC.MQIA_Q_TYPE, CMQC.MQQT_LOCAL);
            request.addParameter(CMQCFC.MQIACF_Q_ATTRS, new int[] {CMQC.MQCA_Q_NAME, CMQC.MQIA_CURRENT_Q_DEPTH});

            PCFMessage[] responses = agent.send(request);
            if (responses != null) {
                for (PCFMessage resp : responses) {
                    String qName =
                            resp.getStringParameterValue(CMQC.MQCA_Q_NAME).trim();
                    if (!config.includeSystemQueues() && IbmMqConnectionConfig.isSystemQueue(qName)) {
                        continue;
                    }
                    int depth = resp.getIntParameterValue(CMQC.MQIA_CURRENT_Q_DEPTH);
                    result.add(toDestination(qName, depth, "PCF"));
                }
            }
            return Collections.unmodifiableList(result);
        } catch (MQException | MQDataException | IOException e) {
            String safeConn = config.connectionId().replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.debug("[IBMMQ-BROKER] PCF inquiry unavailable for [{}], using fallback queues: {}", safeConn, safeErr);
            return fallbackConfiguredDestinations();
        } finally {
            closeQuietly(agent, qmgr);
        }
    }

    private List<AsyncDestination> fallbackConfiguredDestinations() {
        if (config.configuredQueues().isEmpty()) {
            return Collections.emptyList();
        }
        List<AsyncDestination> result = new ArrayList<>();
        try (JMSContext context = openContext()) {
            for (String qName : config.configuredQueues()) {
                int count = countViaBrowser(context, qName);
                result.add(toDestination(qName, count, "CONFIGURED"));
            }
        } catch (JMSException | JMSRuntimeException e) {
            for (String qName : config.configuredQueues()) {
                result.add(toDestination(qName, 0, "OFFLINE"));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private int countViaBrowser(JMSContext context, String queueName) {
        try (QueueBrowser browser = context.createBrowser(context.createQueue(queueName))) {
            Enumeration<?> enumeration = browser.getEnumeration();
            int count = 0;
            while (enumeration != null && enumeration.hasMoreElements()) {
                enumeration.nextElement();
                count++;
            }
            return count;
        } catch (JMSException | JMSRuntimeException e) {
            return 0;
        }
    }

    private boolean tryClearViaPcf(String queueName) {
        MQQueueManager qmgr = null;
        PCFMessageAgent agent = null;
        try {
            qmgr = IbmMqClientFactory.openQueueManager(config);
            agent = IbmMqClientFactory.openPcfAgent(qmgr);
            PCFMessage request = new PCFMessage(CMQCFC.MQCMD_CLEAR_Q);
            request.addParameter(CMQC.MQCA_Q_NAME, queueName);
            agent.send(request);
            return true;
        } catch (MQException | MQDataException | IOException e) {
            String safeDest = queueName.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.debug("[IBMMQ-BROKER] PCF CLEAR_Q fallback to JMS drain for [{}]: {}", safeDest, safeErr);
            return false;
        } finally {
            closeQuietly(agent, qmgr);
        }
    }

    private AsyncDestination toDestination(String qName, int depth, String source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "QUEUE");
        metadata.put("queueManager", config.queueManager());
        metadata.put("channel", config.channel());
        metadata.put("discovery", source);
        return new AsyncDestination(
                "ibmmq:" + config.connectionId() + ":" + qName,
                "ibmmq",
                qName,
                config.connectionId(),
                depth,
                Collections.unmodifiableMap(metadata));
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

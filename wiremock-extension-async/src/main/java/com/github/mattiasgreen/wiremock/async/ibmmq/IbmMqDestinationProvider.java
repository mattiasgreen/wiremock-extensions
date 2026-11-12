package com.github.mattiasgreen.wiremock.async.ibmmq;

import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationMessage;
import com.github.mattiasgreen.wiremock.async.destination.DestinationProvider;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.CMQCFC;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * {@link DestinationProvider} for IBM MQ queues backing the Async Queues &amp; Topics Explorer.
 * Discovers destinations and queue depths dynamically across all {@code ibmmq} connections registered
 * in {@link AsyncBrokerRegistry}, using PCF where authorized and falling back to configured queues + JMS browse.
 *
 * <p><b>Note:</b> Beta functionality subject to evolution.
 */
public class IbmMqDestinationProvider implements DestinationProvider {

    private static final Logger LOG = LoggerFactory.getLogger(IbmMqDestinationProvider.class);
    private static final int MAX_DRAIN_SAFETY = 10_000;

    private final AsyncBrokerRegistry registry;

    public IbmMqDestinationProvider() {
        this(AsyncBrokerRegistry.getInstance());
    }

    public IbmMqDestinationProvider(AsyncBrokerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public String getProtocol() {
        return "ibmmq";
    }

    @Override
    public List<AsyncDestination> listDestinations() {
        List<AsyncDestination> all = new ArrayList<>();
        for (IbmMqConnectionConfig config : listRegisteredConfigs()) {
            all.addAll(discoverForConnection(config));
        }
        return Collections.unmodifiableList(all);
    }

    @Override
    public List<AsyncDestinationMessage> peekMessages(String destination, int limit) {
        if (destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(destination);
        IbmMqConnectionConfig config = resolveTargetConnection(normalizedQueue);
        if (config == null) {
            return Collections.emptyList();
        }

        List<AsyncDestinationMessage> result = new ArrayList<>();
        try (JMSContext context = IbmMqClientFactory.openJmsContext(config)) {
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
                        result.add(IbmMqMessageMapper.toDestinationMessage(normalizedQueue, msg));
                        count++;
                    }
                }
            }
        } catch (JMSException | JMSRuntimeException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.warn("[IBMMQ-PROVIDER] Failed to browse queue [{}]: {}", safeDest, safeErr);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean deleteMessage(String destination, String messageId) {
        if (destination == null || destination.isBlank() || messageId == null || messageId.isBlank()) {
            return false;
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(destination);
        IbmMqConnectionConfig config = resolveTargetConnection(normalizedQueue);
        if (config == null) {
            return false;
        }

        String selector = "JMSMessageID = '" + IbmMqMessageMapper.escapeSelectorLiteral(messageId) + "'";
        try (JMSContext context = IbmMqClientFactory.openJmsContext(config)) {
            Queue queue = context.createQueue(normalizedQueue);
            try (JMSConsumer consumer = context.createConsumer(queue, selector)) {
                return consumer.receiveNoWait() != null;
            }
        } catch (JMSException | JMSRuntimeException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.warn("[IBMMQ-PROVIDER] Failed to delete message on queue [{}]: {}", safeDest, safeErr);
            return false;
        }
    }

    @Override
    public void purgeDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return;
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(destination);
        IbmMqConnectionConfig config = resolveTargetConnection(normalizedQueue);
        if (config == null) {
            return;
        }

        if (tryClearViaPcf(config, normalizedQueue)) {
            return;
        }

        try (JMSContext context = IbmMqClientFactory.openJmsContext(config)) {
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
            LOG.warn("[IBMMQ-PROVIDER] Failed to purge queue [{}]: {}", safeDest, safeErr);
        }
    }

    @Override
    public boolean publishMessage(String destination, AsyncDestinationMessage message) {
        if (destination == null || destination.isBlank() || message == null) {
            return false;
        }
        String normalizedQueue = IbmMqMessageMapper.normalizeQueueName(destination);
        IbmMqConnectionConfig config = resolveTargetConnection(normalizedQueue);
        if (config == null) {
            return false;
        }

        try (JMSContext context = IbmMqClientFactory.openJmsContext(config)) {
            Queue queue = context.createQueue(normalizedQueue);
            TextMessage jmsMessage = IbmMqMessageMapper.toJmsTextMessage(context, message);
            context.createProducer().send(queue, jmsMessage);
            return true;
        } catch (JMSException | JMSRuntimeException e) {
            String safeDest = normalizedQueue.replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.warn("[IBMMQ-PROVIDER] Failed to publish message to queue [{}]: {}", safeDest, safeErr);
            return false;
        }
    }

    private List<IbmMqConnectionConfig> listRegisteredConfigs() {
        List<IbmMqConnectionConfig> list = new ArrayList<>();
        for (BrokerConnection conn : registry.listConnections()) {
            if ("ibmmq".equals(conn.protocol().toLowerCase(Locale.ROOT))) {
                list.add(IbmMqConnectionConfig.from(conn));
            }
        }
        return list;
    }

    private IbmMqConnectionConfig resolveTargetConnection(String normalizedQueue) {
        List<IbmMqConnectionConfig> configs = listRegisteredConfigs();
        if (configs.isEmpty()) {
            return null;
        }
        if (configs.size() == 1) {
            return configs.get(0);
        }
        for (IbmMqConnectionConfig cfg : configs) {
            if (cfg.configuredQueues().contains(normalizedQueue)) {
                return cfg;
            }
        }
        for (IbmMqConnectionConfig cfg : configs) {
            for (AsyncDestination dest : discoverForConnection(cfg)) {
                if (dest.name().equals(normalizedQueue)) {
                    return cfg;
                }
            }
        }
        return configs.get(0);
    }

    private List<AsyncDestination> discoverForConnection(IbmMqConnectionConfig config) {
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
                    result.add(toDestination(config, qName, depth, "PCF"));
                }
            }
            return result;
        } catch (MQException | MQDataException | IOException e) {
            String safeConn = config.connectionId().replaceAll("[\r\n]", "");
            String safeErr = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.debug(
                    "[IBMMQ-PROVIDER] PCF inquiry unavailable for [{}], using fallback queues: {}", safeConn, safeErr);
            return fallbackConfiguredDestinations(config);
        } finally {
            IbmMqMessageBroker.closeQuietly(agent, qmgr);
        }
    }

    private List<AsyncDestination> fallbackConfiguredDestinations(IbmMqConnectionConfig config) {
        if (config.configuredQueues().isEmpty()) {
            return Collections.emptyList();
        }
        List<AsyncDestination> result = new ArrayList<>();
        try (JMSContext context = IbmMqClientFactory.openJmsContext(config)) {
            for (String qName : config.configuredQueues()) {
                int count = countViaBrowser(context, qName);
                result.add(toDestination(config, qName, count, "CONFIGURED"));
            }
        } catch (JMSException | JMSRuntimeException e) {
            for (String qName : config.configuredQueues()) {
                result.add(toDestination(config, qName, 0, "OFFLINE"));
            }
        }
        return result;
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

    private boolean tryClearViaPcf(IbmMqConnectionConfig config, String queueName) {
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
            LOG.debug("[IBMMQ-PROVIDER] PCF CLEAR_Q fallback to JMS drain for [{}]: {}", safeDest, safeErr);
            return false;
        } finally {
            IbmMqMessageBroker.closeQuietly(agent, qmgr);
        }
    }

    private AsyncDestination toDestination(IbmMqConnectionConfig config, String qName, int depth, String source) {
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
}

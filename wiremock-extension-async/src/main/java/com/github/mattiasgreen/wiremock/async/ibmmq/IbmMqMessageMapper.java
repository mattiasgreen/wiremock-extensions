package com.github.mattiasgreen.wiremock.async.ibmmq;

import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationMessage;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TextMessage;

/**
 * Bidirectional mapper between IBM MQ JMS/MQMD messages and WireMock async message models.
 */
final class IbmMqMessageMapper {

    private static final String QUEUE_URI_SCHEME = "queue://";

    private IbmMqMessageMapper() {}

    static AsyncMessage toAsyncMessage(String destination, Message jmsMessage) throws JMSException {
        Objects.requireNonNull(jmsMessage, "jmsMessage must not be null");
        String payload = extractPayload(jmsMessage);
        String msgId = normalizeMessageId(jmsMessage.getJMSMessageID());
        String correlationId = jmsMessage.getJMSCorrelationID();
        String replyTo = extractQueueName(jmsMessage.getJMSReplyTo());
        long timestampMs = jmsMessage.getJMSTimestamp();
        Instant ts = timestampMs > 0 ? Instant.ofEpochMilli(timestampMs) : Instant.now();
        Map<String, String> headers = extractHeaders(jmsMessage);

        return AsyncMessage.builder()
                .id(msgId)
                .destination(destination)
                .correlationId(correlationId)
                .replyTo(replyTo)
                .payload(payload)
                .headers(headers)
                .timestamp(ts)
                .build();
    }

    static AsyncDestinationMessage toDestinationMessage(String destination, Message jmsMessage) throws JMSException {
        AsyncMessage base = toAsyncMessage(destination, jmsMessage);
        String payload = Objects.requireNonNullElse(base.payload(), "");
        int sizeBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        return new AsyncDestinationMessage(
                base.id(),
                "ibmmq",
                destination,
                base.key(),
                base.correlationId(),
                base.replyTo(),
                base.timestamp(),
                sizeBytes,
                base.headers(),
                payload,
                null,
                null);
    }

    static TextMessage toJmsTextMessage(JMSContext context, AsyncMessage asyncMessage) throws JMSException {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(asyncMessage, "asyncMessage must not be null");
        String payload = Objects.requireNonNullElse(asyncMessage.payload(), "");
        TextMessage jmsMessage = context.createTextMessage(payload);

        if (asyncMessage.correlationId() != null
                && !asyncMessage.correlationId().isBlank()) {
            jmsMessage.setJMSCorrelationID(asyncMessage.correlationId());
        }
        if (asyncMessage.replyTo() != null && !asyncMessage.replyTo().isBlank()) {
            jmsMessage.setJMSReplyTo(context.createQueue(normalizeQueueName(asyncMessage.replyTo())));
        }
        if (asyncMessage.headers() != null) {
            for (Map.Entry<String, String> entry : asyncMessage.headers().entrySet()) {
                String key = toValidJmsPropertyName(entry.getKey());
                String value = entry.getValue();
                if (key == null || value == null || isReadOnlyJmsProperty(key)) {
                    continue;
                }
                jmsMessage.setStringProperty(key, value);
            }
        }
        return jmsMessage;
    }

    static TextMessage toJmsTextMessage(JMSContext context, AsyncDestinationMessage msg) throws JMSException {
        AsyncMessage asyncMsg = AsyncMessage.builder()
                .id(msg.id())
                .destination(msg.destination())
                .key(msg.key())
                .correlationId(msg.correlationId())
                .replyTo(msg.replyTo())
                .payload(msg.payload())
                .headers(msg.headers())
                .timestamp(msg.timestamp() != null ? msg.timestamp() : Instant.now())
                .build();
        return toJmsTextMessage(context, asyncMsg);
    }

    static String normalizeQueueName(String queueName) {
        if (queueName == null) {
            return "";
        }
        String trimmed = queueName.trim();
        if (trimmed.startsWith(QUEUE_URI_SCHEME)) {
            int slashIdx = trimmed.indexOf('/', QUEUE_URI_SCHEME.length());
            trimmed = slashIdx >= 0 ? trimmed.substring(slashIdx + 1) : trimmed.substring(QUEUE_URI_SCHEME.length());
        }
        int queryIdx = trimmed.indexOf('?');
        return queryIdx >= 0 ? trimmed.substring(0, queryIdx) : trimmed;
    }

    static String extractPayload(Message message) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            return Objects.requireNonNullElse(textMessage.getText(), "");
        }
        if (message instanceof BytesMessage bytesMessage) {
            bytesMessage.reset();
            long length = bytesMessage.getBodyLength();
            if (length <= 0) {
                return "";
            }
            if (length > Integer.MAX_VALUE) {
                throw new JMSException("BytesMessage exceeds max supported buffer size: " + length);
            }
            byte[] data = new byte[(int) length];
            int read = bytesMessage.readBytes(data);
            return read > 0 ? new String(data, 0, read, StandardCharsets.UTF_8) : "";
        }
        return Objects.toString(message.getBody(Object.class), "");
    }

    static Map<String, String> extractHeaders(Message message) throws JMSException {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<?> names = message.getPropertyNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                Object rawName = names.nextElement();
                if (rawName != null) {
                    String propName = rawName.toString();
                    Object val = message.getObjectProperty(propName);
                    if (val != null) {
                        headers.put(propName, val.toString());
                    }
                }
            }
        }
        return Collections.unmodifiableMap(headers);
    }

    static String escapeSelectorLiteral(String value) {
        return Objects.requireNonNullElse(value, "").replace("'", "''");
    }

    /**
     * Normalizes HTTP/Kafka hyphenated header names (e.g. {@code x-tenant-id}) into valid JMS
     * Java-identifier property names ({@code x_tenant_id}) required by the JMS specification.
     */
    static String toValidJmsPropertyName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(rawName.length());
        for (int i = 0; i < rawName.length(); i++) {
            char c = rawName.charAt(i);
            if (i == 0) {
                if (Character.isJavaIdentifierStart(c)) {
                    sb.append(c);
                } else {
                    sb.append('_');
                    if (Character.isJavaIdentifierPart(c)) {
                        sb.append(c);
                    }
                }
            } else {
                sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
            }
        }
        return sb.toString();
    }

    private static String normalizeMessageId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return rawId;
    }

    private static String extractQueueName(Destination destination) throws JMSException {
        if (destination instanceof Queue queue) {
            return normalizeQueueName(queue.getQueueName());
        }
        return null;
    }

    private static boolean isReadOnlyJmsProperty(String key) {
        return key.startsWith("JMSXDeliveryCount")
                || key.startsWith("JMS_IBM_PutDate")
                || key.startsWith("JMS_IBM_PutTime")
                || key.startsWith("JMS_IBM_MsgType");
    }
}

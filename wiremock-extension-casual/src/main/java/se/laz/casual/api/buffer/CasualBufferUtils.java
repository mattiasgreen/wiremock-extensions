package se.laz.casual.api.buffer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.buffer.type.fielded.FieldedData;
import se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer;
import se.laz.casual.config.ConfigurationOptions;
import se.laz.casual.config.ConfigurationService;

/**
 * Utility functions for Casual buffers, including text/JSON extraction, human-readable
 * FML32 (.FIELDED) JSON translation, CSTRING null-termination management, Base64
 * binary encoding/decoding for raw octet buffers, and hex dumps.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-api}.
 */
public final class CasualBufferUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CasualBufferUtils() {}

    /**
     * Determines whether Casual fielded definitions (FML) are available.
     * <p>
     * Early exits to {@code false} if {@code casual-fields.json} cannot be found on the classpath,
     * or if {@code CASUAL_FIELD_TABLE} is unset, or if the file that {@code CASUAL_FIELD_TABLE}
     * points to does not exist.
     */
    public static boolean isFieldTableAvailable() {
        String envFile = ConfigurationService.getConfiguration(ConfigurationOptions.CASUAL_FIELD_TABLE);
        if (envFile == null || envFile.isBlank()) {
            envFile = System.getenv("CASUAL_FIELD_TABLE");
        }
        if (envFile == null || envFile.isBlank()) {
            envFile = System.getProperty("CASUAL_FIELD_TABLE");
        }
        if (envFile != null && !envFile.isBlank()) {
            try {
                return Files.exists(Path.of(envFile.trim()));
            } catch (Exception ignored) {
                return false;
            }
        }
        try {
            ClassLoader cl = CasualBufferUtils.class.getClassLoader();
            if (cl != null && cl.getResource("casual-fields.json") != null) {
                return true;
            }
            ClassLoader ccl = Thread.currentThread().getContextClassLoader();
            if (ccl != null && ccl.getResource("casual-fields.json") != null) {
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    /**
     * Determines whether the specified buffer type represents JSON.
     */
    public static boolean isJsonType(String bufferType) {
        if (bufferType == null || bufferType.isBlank()) {
            return true;
        }
        String normalized = bufferType.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("json");
    }

    /**
     * Determines whether the specified buffer type represents CSTRING.
     */
    public static boolean isCStringType(String bufferType) {
        if (bufferType == null || bufferType.isBlank()) {
            return false;
        }
        String normalized = bufferType.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("cstring");
    }

    /**
     * Determines whether the specified buffer type represents FML32 / FIELDED data.
     */
    public static boolean isFieldedType(String bufferType) {
        if (bufferType == null || bufferType.isBlank()) {
            return false;
        }
        String normalized = bufferType.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("field");
    }

    /**
     * Combines all payload chunks in a {@link ServiceBuffer} into a single byte array.
     */
    public static byte[] toByteArray(ServiceBuffer buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (byte[] part : buffer.getBytes()) {
            if (part != null && part.length > 0) {
                baos.write(part, 0, part.length);
            }
        }
        return baos.toByteArray();
    }

    /**
     * Extracts payload from a {@link ServiceBuffer}.
     * Returns:
     * <ul>
     *   <li>UTF-8 text for JSON buffers</li>
     *   <li>UTF-8 text without trailing null-terminator for CSTRING buffers</li>
     *   <li>Structured JSON object for .FIELDED (FML) buffers (or Base64 on decode failure)</li>
     *   <li>Base64 string for binary buffers (.X_OCTET)</li>
     * </ul>
     */
    public static String extractPayload(ServiceBuffer buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return "";
        }
        String type = buffer.getType();
        if (isJsonType(type)) {
            StringBuilder sb = new StringBuilder();
            for (byte[] part : buffer.getBytes()) {
                if (part != null) {
                    sb.append(new String(part, StandardCharsets.UTF_8));
                }
            }
            return sb.toString();
        }
        if (isCStringType(type)) {
            StringBuilder sb = new StringBuilder();
            for (byte[] part : buffer.getBytes()) {
                if (part != null) {
                    sb.append(new String(part, StandardCharsets.UTF_8));
                }
            }
            String text = sb.toString();
            while (text.endsWith("\0")) {
                text = text.substring(0, text.length() - 1);
            }
            return text;
        }
        if (isFieldedType(type)) {
            if (!isFieldTableAvailable()) {
                return Base64.getEncoder().encodeToString(toByteArray(buffer));
            }
            try {
                FieldedTypeBuffer fielded = FieldedTypeBuffer.create(buffer.getBytes());
                Map<String, List<FieldedData<?>>> rawMap = fielded.extract();
                if (!rawMap.isEmpty()) {
                    Map<String, Object> jsonMap = new LinkedHashMap<>();
                    for (Map.Entry<String, List<FieldedData<?>>> entry : rawMap.entrySet()) {
                        List<FieldedData<?>> list = entry.getValue();
                        if (list == null || list.isEmpty()) {
                            continue;
                        }
                        List<Object> values = new ArrayList<>();
                        for (FieldedData<?> item : list) {
                            Object data = item.getData();
                            if (data instanceof byte[] bytes) {
                                values.add(Base64.getEncoder().encodeToString(bytes));
                            } else if (data instanceof Character c) {
                                values.add(String.valueOf(c));
                            } else {
                                values.add(data);
                            }
                        }
                        jsonMap.put(entry.getKey(), values.size() == 1 ? values.get(0) : values);
                    }
                    return OBJECT_MAPPER.writeValueAsString(jsonMap);
                }
            } catch (Throwable ignored) {
                // Fall back to Base64 if not in CasualFieldedLookup, missing field table, or raw FML decode fails
            }
        }
        return Base64.getEncoder().encodeToString(toByteArray(buffer));
    }

    /**
     * Wraps payload text back into a {@link ServiceBuffer} based on buffer type.
     * Decodes Base64 for non-JSON buffers, wraps UTF-8 for JSON and CSTRING,
     * or parses JSON object into a binary .FIELDED buffer.
     */
    public static ServiceBuffer wrapPayload(String bufferType, String payload) {
        if (payload == null || payload.isBlank()) {
            return ServiceBuffer.empty();
        }
        String type = (bufferType != null && !bufferType.isBlank()) ? bufferType.trim() : "JSON";
        if (isJsonType(type)) {
            return ServiceBuffer.of(JsonBuffer.of(payload));
        }
        if (isCStringType(type)) {
            String text = payload.endsWith("\0") ? payload : payload + "\0";
            return ServiceBuffer.of("CSTRING/", List.of(text.getBytes(StandardCharsets.UTF_8)));
        }
        if (isFieldedType(type)) {
            if (!isFieldTableAvailable()) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(payload.trim());
                    return ServiceBuffer.of("CFIELD/", List.of(decoded));
                } catch (IllegalArgumentException e) {
                    return ServiceBuffer.of("CFIELD/", List.of(payload.getBytes(StandardCharsets.UTF_8)));
                }
            }
            String trimmed = payload.trim();
            if (trimmed.startsWith("{")) {
                try {
                    Map<?, ?> jsonMap = OBJECT_MAPPER.readValue(trimmed, Map.class);
                    FieldedTypeBuffer fielded = FieldedTypeBuffer.create();
                    for (Map.Entry<?, ?> entry : jsonMap.entrySet()) {
                        String key = String.valueOf(entry.getKey());
                        Object val = entry.getValue();
                        if (val instanceof List<?> list) {
                            for (Object item : list) {
                                writeFieldedValue(fielded, key, item);
                            }
                        } else {
                            writeFieldedValue(fielded, key, val);
                        }
                    }
                    return ServiceBuffer.of("CFIELD/", fielded.encode());
                } catch (Throwable ignored) {
                    // Fall back to Base64 decode below if JSON mapping or field write fails
                }
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(trimmed);
                return ServiceBuffer.of("CFIELD/", List.of(decoded));
            } catch (IllegalArgumentException e) {
                return ServiceBuffer.of("CFIELD/", List.of(payload.getBytes(StandardCharsets.UTF_8)));
            }
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(payload.trim());
            return ServiceBuffer.of(type, List.of(decoded));
        } catch (IllegalArgumentException e) {
            // Fall back to UTF-8 raw bytes if not valid Base64
            return ServiceBuffer.of(type, List.of(payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static void writeFieldedValue(FieldedTypeBuffer fielded, String key, Object val) {
        if (val == null) {
            return;
        }
        if (val instanceof String s) {
            fielded.write(key, s);
        } else if (val instanceof Integer i) {
            fielded.write(key, i);
        } else if (val instanceof Long l) {
            fielded.write(key, l);
        } else if (val instanceof Short s) {
            fielded.write(key, s);
        } else if (val instanceof Float f) {
            fielded.write(key, f);
        } else if (val instanceof Double d) {
            fielded.write(key, d);
        } else if (val instanceof Character c) {
            fielded.write(key, c);
        } else if (val instanceof byte[] b) {
            fielded.write(key, b);
        } else {
            fielded.write(key, String.valueOf(val));
        }
    }

    /**
     * Formats a byte array as a concise hex dump for TRACE level diagnostic logging.
     */
    public static String toHexDump(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) {
            return "<empty>";
        }
        int len = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder(len * 3 + 32);
        for (int i = 0; i < len; i++) {
            sb.append(String.format(Locale.ROOT, "%02X ", bytes[i]));
            if ((i + 1) % 16 == 0 && i + 1 < len) {
                sb.append("\n");
            }
        }
        if (bytes.length > maxBytes) {
            sb.append("... [").append(bytes.length - maxBytes).append(" more bytes]");
        }
        return sb.toString();
    }
}

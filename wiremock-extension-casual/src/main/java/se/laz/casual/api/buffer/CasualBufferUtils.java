package se.laz.casual.api.buffer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.buffer.type.fielded.FieldType;
import se.laz.casual.api.buffer.type.fielded.FieldedData;
import se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer;
import se.laz.casual.api.buffer.type.fielded.json.CasualField;
import se.laz.casual.api.buffer.type.fielded.json.CasualFielded;
import se.laz.casual.api.buffer.type.fielded.json.CasualFieldedLookup;
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

    private static final Logger LOG = LoggerFactory.getLogger(CasualBufferUtils.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static volatile String lastLoadedTablePath = null;

    private CasualBufferUtils() {}

    /**
     * Determines whether Casual fielded definitions (FML) are available.
     * <p>
     * Early exits to {@code false} if {@code casual-fields.json} cannot be found on the classpath,
     * or if {@code CASUAL_FIELD_TABLE} is unset, or if the file that {@code CASUAL_FIELD_TABLE}
     * points to does not exist.
     */
    public static boolean isFieldTableAvailable() {
        String envFile = System.getenv("CASUAL_FIELD_TABLE");
        if (envFile == null || envFile.isBlank()) {
            envFile = System.getProperty("CASUAL_FIELD_TABLE");
        }
        if (envFile != null && !envFile.isBlank()) {
            try {
                Path path = Path.of(envFile.trim());
                if (Files.exists(path)) {
                    String canonical = path.toAbsolutePath().normalize().toString();
                    ConfigurationService.setConfiguration(ConfigurationOptions.CASUAL_FIELD_TABLE, canonical);
                    if (!canonical.equals(lastLoadedTablePath)
                            || CasualFieldedLookup.getNames().isEmpty()) {
                        reloadFieldTable();
                    }
                    return true;
                }
                lastLoadedTablePath = null;
                return false;
            } catch (java.nio.file.InvalidPathException | SecurityException ignored) {
                lastLoadedTablePath = null;
                return false;
            }
        }
        String conf = ConfigurationService.getConfiguration(ConfigurationOptions.CASUAL_FIELD_TABLE);
        if (conf != null && !conf.isBlank()) {
            try {
                Path path = Path.of(conf.trim());
                if (Files.exists(path)) {
                    String canonical = path.toAbsolutePath().normalize().toString();
                    if (!canonical.equals(lastLoadedTablePath)
                            || CasualFieldedLookup.getNames().isEmpty()) {
                        reloadFieldTable();
                    }
                    return true;
                }
                lastLoadedTablePath = null;
                return false;
            } catch (java.nio.file.InvalidPathException | SecurityException ignored) {
                lastLoadedTablePath = null;
                return false;
            }
        }
        ClassLoader cl = CasualBufferUtils.class.getClassLoader();
        if (cl != null && cl.getResource("casual-fields.json") != null) {
            return true;
        }
        ClassLoader ccl = Thread.currentThread().getContextClassLoader();
        return ccl != null && ccl.getResource("casual-fields.json") != null;
    }

    /**
     * Dynamically reloads Casual fielded definitions from the file pointed to by {@code CASUAL_FIELD_TABLE}.
     */
    public static synchronized void reloadFieldTable() {
        String envFile = System.getenv("CASUAL_FIELD_TABLE");
        if (envFile == null || envFile.isBlank()) {
            envFile = System.getProperty("CASUAL_FIELD_TABLE");
        }
        if (envFile == null || envFile.isBlank()) {
            envFile = ConfigurationService.getConfiguration(ConfigurationOptions.CASUAL_FIELD_TABLE);
        }
        if (envFile == null || envFile.isBlank()) {
            lastLoadedTablePath = null;
            return;
        }
        try {
            Path path = Path.of(envFile.trim());
            if (!Files.exists(path)) {
                lastLoadedTablePath = null;
                return;
            }
            URL url = path.toUri().toURL();

            Field stringToFieldField = CasualFieldedLookup.class.getDeclaredField("stringToField");
            stringToFieldField.setAccessible(true);
            Field realIdToFieldField = CasualFieldedLookup.class.getDeclaredField("realIdToField");
            realIdToFieldField.setAccessible(true);
            Field jsonURLField = CasualFieldedLookup.class.getDeclaredField("jsonURL");
            jsonURLField.setAccessible(true);

            jsonURLField.set(null, url);

            Method slurpMethod = CasualFieldedLookup.class.getDeclaredMethod("slurpJSON", URL.class);
            slurpMethod.setAccessible(true);
            Object casualFielded = slurpMethod.invoke(null, url);

            Map<String, CasualField> newStringToField = new HashMap<>();
            Map<Long, CasualField> newRealIdToField = new HashMap<>();
            stringToFieldField.set(null, newStringToField);
            realIdToFieldField.set(null, newRealIdToField);

            Method initMethod = CasualFieldedLookup.class.getDeclaredMethod("initializeData", CasualFielded.class);
            initMethod.setAccessible(true);
            initMethod.invoke(null, casualFielded);

            stringToFieldField.set(null, Collections.unmodifiableMap(newStringToField));
            realIdToFieldField.set(null, Collections.unmodifiableMap(newRealIdToField));

            lastLoadedTablePath = path.toAbsolutePath().normalize().toString();
            LOG.info(
                    "[CASUAL-FIELDED-RELOADED] Successfully loaded {} field definitions from {}",
                    newStringToField.size(),
                    lastLoadedTablePath.replace('\r', '_').replace('\n', '_'));
        } catch (Exception | LinkageError t) {
            LOG.warn(
                    "[CASUAL-FIELDED-RELOAD-FAIL] Failed to reload field table: {}",
                    t.getMessage() != null ? t.getMessage().replace('\r', '_').replace('\n', '_') : "null");
        }
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
                            Object parsedJson = tryParseJsonPayload(data);
                            if (parsedJson != null) {
                                values.add(parsedJson);
                            } else if (data instanceof byte[] bytes) {
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
        String type = (bufferType != null && !bufferType.isBlank()) ? bufferType.trim() : "JSON";
        if (isJsonType(type)) {
            String json = (payload != null && !payload.isBlank()) ? payload : "{}";
            return ServiceBuffer.of(JsonBuffer.of(json));
        }
        if (payload == null || payload.isBlank()) {
            return ServiceBuffer.of(type, List.of());
        }
        if (isCStringType(type)) {
            String text = payload.endsWith("\0") ? payload : payload + "\0";
            return ServiceBuffer.of("CSTRING/", List.of(text.getBytes(StandardCharsets.UTF_8)));
        }
        if (isFieldedType(type)) {
            String trimmed = payload.trim();
            if (trimmed.startsWith("{")) {
                if (isFieldTableAvailable()) {
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
                    } catch (Throwable t) {
                        LOG.error(
                                "[CASUAL-FIELDED-ENCODE-FAIL] Failed to encode JSON payload into .FIELDED (FML) buffer: {}",
                                t.getMessage(),
                                t);
                    }
                } else {
                    LOG.error(
                            "[CASUAL-FIELDED-NO-TABLE] Cannot encode JSON object into binary CFIELD/ buffer because casual field table is not configured. Returning empty buffer.");
                    return ServiceBuffer.of("CFIELD/", List.of(new byte[0]));
                }
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(trimmed);
                return ServiceBuffer.of("CFIELD/", List.of(decoded));
            } catch (IllegalArgumentException e) {
                LOG.error(
                        "[CASUAL-FIELDED-INVALID-PAYLOAD] Payload for CFIELD/ is neither valid JSON nor valid Base64 binary buffer. Returning empty buffer.");
                return ServiceBuffer.of("CFIELD/", List.of(new byte[0]));
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
        Optional<CasualField> fieldOpt = CasualFieldedLookup.forName(key);
        if (fieldOpt.isEmpty()) {
            String sanitizedKey = key.replace('\r', '_').replace('\n', '_');
            LOG.warn(
                    "[CASUAL-FIELDED-UNKNOWN] Field [{}] not found in casual-fields definition table. Skipping.",
                    sanitizedKey);
            return;
        }
        FieldType fieldType = fieldOpt.get().getType();
        try {
            switch (fieldType) {
                case CASUAL_FIELD_BINARY -> {
                    byte[] bytes;
                    if (val instanceof byte[] b) {
                        bytes = b;
                    } else if (val instanceof String s) {
                        try {
                            bytes = Base64.getDecoder().decode(s.trim());
                        } catch (IllegalArgumentException ignored) {
                            bytes = s.getBytes(StandardCharsets.UTF_8);
                        }
                    } else if (val instanceof Map || val instanceof List) {
                        bytes = OBJECT_MAPPER.writeValueAsBytes(val);
                    } else {
                        bytes = String.valueOf(val).getBytes(StandardCharsets.UTF_8);
                    }
                    fielded.write(key, bytes);
                }
                case CASUAL_FIELD_LONG -> {
                    long l;
                    if (val instanceof Number n) {
                        l = n.longValue();
                    } else {
                        l = Long.parseLong(String.valueOf(val).trim());
                    }
                    fielded.write(key, l);
                }
                case CASUAL_FIELD_SHORT -> {
                    short s;
                    if (val instanceof Number n) {
                        s = n.shortValue();
                    } else {
                        s = Short.parseShort(String.valueOf(val).trim());
                    }
                    fielded.write(key, s);
                }
                case CASUAL_FIELD_FLOAT -> {
                    float f;
                    if (val instanceof Number n) {
                        f = n.floatValue();
                    } else {
                        f = Float.parseFloat(String.valueOf(val).trim());
                    }
                    fielded.write(key, f);
                }
                case CASUAL_FIELD_DOUBLE -> {
                    double d;
                    if (val instanceof Number n) {
                        d = n.doubleValue();
                    } else {
                        d = Double.parseDouble(String.valueOf(val).trim());
                    }
                    fielded.write(key, d);
                }
                case CASUAL_FIELD_CHAR -> {
                    char c;
                    if (val instanceof Character ch) {
                        c = ch;
                    } else if (val instanceof Number n) {
                        c = (char) n.intValue();
                    } else {
                        String str = String.valueOf(val);
                        c = str.isEmpty() ? ' ' : str.charAt(0);
                    }
                    fielded.write(key, c);
                }
                case CASUAL_FIELD_STRING -> {
                    String s;
                    if (val instanceof String str) {
                        s = str;
                    } else if (val instanceof Map || val instanceof List) {
                        s = OBJECT_MAPPER.writeValueAsString(val);
                    } else {
                        s = String.valueOf(val);
                    }
                    fielded.write(key, s);
                }
            }
        } catch (IllegalArgumentException
                | IndexOutOfBoundsException
                | com.fasterxml.jackson.core.JsonProcessingException
                | se.laz.casual.api.buffer.type.fielded.json.CasualFieldedLookupException e) {
            String sanitizedKey = key.replace('\r', '_').replace('\n', '_');
            LOG.warn(
                    "[CASUAL-FIELDED-WRITE-FAIL] Failed to write field [{}] as type [{}]: {}",
                    sanitizedKey,
                    fieldType,
                    e.getMessage());
        }
    }

    /**
     * Wraps raw binary bytes directly into a {@link ServiceBuffer}.
     */
    public static ServiceBuffer wrapPayload(String bufferType, byte[] binaryData) {
        String type = (bufferType != null && !bufferType.isBlank()) ? bufferType.trim() : "X_OCTET";
        if (isJsonType(type)) {
            String json = (binaryData != null && binaryData.length > 0)
                    ? new String(binaryData, StandardCharsets.UTF_8)
                    : "{}";
            return ServiceBuffer.of(JsonBuffer.of(json));
        }
        if (binaryData == null || binaryData.length == 0) {
            return ServiceBuffer.of(type, List.of());
        }
        return ServiceBuffer.of(type, List.of(binaryData));
    }

    /**
     * Parses a hex string (e.g. "01 02 AA BB" or "0102AABB" or "0x01, 0x02") into raw bytes.
     */
    public static byte[] fromHex(String hexString) {
        if (hexString == null || hexString.isBlank()) {
            return new byte[0];
        }
        String cleaned = hexString.replaceAll("(?:0x|0X|[^0-9a-fA-F])", "");
        if (cleaned.isEmpty()) {
            return new byte[0];
        }
        if (cleaned.length() % 2 != 0) {
            cleaned = "0" + cleaned;
        }
        byte[] result = new byte[cleaned.length() / 2];
        for (int i = 0; i < cleaned.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(cleaned.substring(i, i + 2), 16);
        }
        return result;
    }

    /**
     * Formats bytes as a canonical multi-line hex dump with byte offsets, hex pairs, and ASCII representation.
     */
    public static String toCanonicalHexDump(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) {
            return "<empty>";
        }
        int len = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i += 16) {
            sb.append(String.format(Locale.ROOT, "%08X  ", i));
            int chunk = Math.min(16, len - i);
            for (int j = 0; j < 16; j++) {
                if (j < chunk) {
                    sb.append(String.format(Locale.ROOT, "%02X ", bytes[i + j]));
                } else {
                    sb.append("   ");
                }
                if (j == 7) sb.append(" ");
            }
            sb.append(" |");
            for (int j = 0; j < chunk; j++) {
                int val = bytes[i + j] & 0xFF;
                char c = (val >= 32 && val < 127) ? (char) val : '.';
                sb.append(c);
            }
            sb.append(String.format(Locale.ROOT, "|%n"));
        }
        if (bytes.length > maxBytes) {
            sb.append(String.format(Locale.ROOT, "... [%d more bytes]%n", bytes.length - maxBytes));
        }
        return sb.toString();
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

    private static Object tryParseJsonPayload(Object data) {
        if (data == null) {
            return null;
        }
        String str = null;
        if (data instanceof byte[] bytes) {
            if (bytes.length > 0) {
                str = new String(bytes, StandardCharsets.UTF_8).trim();
            }
        } else if (data instanceof String s) {
            str = s.trim();
        }
        if (str == null || str.isEmpty()) {
            return null;
        }

        // 1. Direct JSON object or array
        if ((str.startsWith("{") && str.endsWith("}")) || (str.startsWith("[") && str.endsWith("]"))) {
            try {
                return OBJECT_MAPPER.readValue(str, Object.class);
            } catch (IOException ignored) {
            }
        }

        // 2. Base64-encoded JSON payload
        try {
            byte[] decoded = Base64.getDecoder().decode(str);
            String decodedStr = new String(decoded, StandardCharsets.UTF_8).trim();
            if ((decodedStr.startsWith("{") && decodedStr.endsWith("}"))
                    || (decodedStr.startsWith("[") && decodedStr.endsWith("]"))) {
                return OBJECT_MAPPER.readValue(decodedStr, Object.class);
            }
        } catch (IOException | IllegalArgumentException ignored) {
        }
        return null;
    }
}

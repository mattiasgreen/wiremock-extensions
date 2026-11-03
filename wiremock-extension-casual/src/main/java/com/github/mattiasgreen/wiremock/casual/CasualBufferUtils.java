package com.github.mattiasgreen.wiremock.casual;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.buffer.type.fielded.Constants;
import se.laz.casual.api.buffer.type.fielded.FieldType;
import se.laz.casual.api.buffer.type.fielded.json.CasualField;
import se.laz.casual.api.buffer.type.fielded.json.CasualFielded;
import se.laz.casual.api.buffer.type.fielded.json.CasualFieldedLookup;
import se.laz.casual.config.ConfigurationOptions;
import se.laz.casual.config.ConfigurationService;

/**
 * Utility functions for Casual buffers, including text/JSON extraction, human-readable
 * FML32 (.FIELDED) JSON translation, CSTRING null-termination management, Base64
 * binary encoding/decoding for raw octet buffers, and hex dumps.
 */
public final class CasualBufferUtils {

    public static final String FIELD_KEY_PREFIX = "FLD_";
    public static final Pattern FIELD_KEY_PATTERN = Pattern.compile("^FLD_(\\d+)$");

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
        return normalized.contains("cstring") || normalized.equals("string");
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
    /**
     * Resolves a {@link Charset} from an explicit charset name or HTTP Content-Type header value,
     * defaulting to {@link StandardCharsets#UTF_8} if unspecified or unsupported.
     */
    public static Charset resolveCharset(String charsetOrContentType) {
        if (charsetOrContentType == null || charsetOrContentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        String raw = charsetOrContentType.trim();
        int idx = raw.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx >= 0) {
            raw = raw.substring(idx + "charset=".length()).trim();
            int semi = raw.indexOf(';');
            if (semi >= 0) {
                raw = raw.substring(0, semi).trim();
            }
        }
        raw = raw.replace("\"", "").replace("'", "").trim();
        if (raw.isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(raw);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Resolves a {@link Charset} with precedence: explicit {@code X-Casual-Charset} over {@code Content-Type}.
     */
    public static Charset resolveCharset(String explicitCasualCharset, String contentTypeHeader) {
        if (explicitCasualCharset != null && !explicitCasualCharset.isBlank()) {
            return resolveCharset(explicitCasualCharset);
        }
        return resolveCharset(contentTypeHeader);
    }

    /**
     * Detects the character set of a {@link ServiceBuffer} by inspecting XML prologs and validating
     * UTF-8 byte sequences, falling back to {@code ISO-8859-1} when non-UTF-8 single-byte sequences are present.
     */
    public static Charset detectBufferCharset(ServiceBuffer buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        byte[] bytes = toByteArray(buffer);
        if (bytes.length == 0) {
            return StandardCharsets.UTF_8;
        }
        int probeLen = Math.min(bytes.length, 256);
        String asciiProbe = new String(bytes, 0, probeLen, StandardCharsets.ISO_8859_1);
        int prologEnd = asciiProbe.indexOf("?>");
        if (asciiProbe.startsWith("<?xml") && prologEnd > 0) {
            String prolog = asciiProbe.substring(0, prologEnd);
            int encIdx = prolog.toLowerCase(Locale.ROOT).indexOf("encoding=");
            if (encIdx > 0) {
                String after = prolog.substring(encIdx + "encoding=".length()).trim();
                if (!after.isEmpty() && (after.charAt(0) == '"' || after.charAt(0) == '\'')) {
                    char quote = after.charAt(0);
                    int closeIdx = after.indexOf(quote, 1);
                    if (closeIdx > 1) {
                        return resolveCharset(after.substring(1, closeIdx));
                    }
                }
            }
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (java.nio.charset.CharacterCodingException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private static String decodeBufferChunks(ServiceBuffer buffer, Charset charset) {
        return new String(toByteArray(buffer), charset);
    }

    /**
     * Extracts payload from a {@link ServiceBuffer} using UTF-8 default encoding.
     */
    public static String extractPayload(ServiceBuffer buffer) {
        return extractPayload(buffer, StandardCharsets.UTF_8);
    }

    /**
     * Extracts payload from a {@link ServiceBuffer} using the specified {@link Charset}.
     */
    public static String extractPayload(ServiceBuffer buffer, Charset charset) {
        if (buffer == null || buffer.isEmpty()) {
            return "";
        }
        Charset effectiveCharset = charset != null ? charset : StandardCharsets.UTF_8;
        String type = buffer.getType();
        if (isJsonType(type)) {
            return decodeBufferChunks(buffer, effectiveCharset);
        }
        if (isCStringType(type)) {
            String text = decodeBufferChunks(buffer, effectiveCharset);
            while (text.endsWith("\0")) {
                text = text.substring(0, text.length() - 1);
            }
            return text;
        }
        if (isFieldedType(type)) {
            byte[] bytes = toByteArray(buffer);
            if (bytes.length == 0) {
                return "{}";
            }
            try {
                Map<String, Object> jsonMap = decodeFieldedBufferToJsonMap(bytes, effectiveCharset);
                if (!jsonMap.isEmpty()) {
                    return OBJECT_MAPPER.writeValueAsString(jsonMap);
                }
            } catch (Throwable t) {
                LOG.debug(
                        "[CASUAL-FIELDED-DECODE-FAIL] Failed to decode fielded buffer, falling back to Base64: {}",
                        t.getMessage());
            }
            return Base64.getEncoder().encodeToString(bytes);
        }
        return Base64.getEncoder().encodeToString(toByteArray(buffer));
    }

    /**
     * Decodes binary fielded buffer bytes into a structured JSON map, utilizing CasualFieldedLookup for known
     * fields and falling back to FLD_<realId> keys when field table lookups are absent or unmapped.
     */
    private static Map<String, Object> decodeFieldedBufferToJsonMap(byte[] bytes, Charset effectiveCharset) {
        Map<String, List<Object>> multiMap = new LinkedHashMap<>();
        int currentIndex = 0;
        ByteBuffer buf = ByteBuffer.wrap(bytes);

        while (currentIndex + 16 <= bytes.length) {
            long realFieldId = buf.getLong(currentIndex);
            currentIndex += 8;
            long fieldSizeLong = buf.getLong(currentIndex);
            currentIndex += 8;
            if (fieldSizeLong < 0 || fieldSizeLong > (bytes.length - currentIndex)) {
                throw new IllegalArgumentException("Invalid fielded buffer size: " + fieldSizeLong);
            }
            int fieldSize = (int) fieldSizeLong;
            byte[] fieldData = new byte[fieldSize];
            buf.position(currentIndex);
            buf.get(fieldData);
            currentIndex += fieldSize;

            String fieldName;
            FieldType fieldType;
            Optional<CasualField> fieldOpt = Optional.empty();
            if (isFieldTableAvailable()) {
                try {
                    fieldOpt = CasualFieldedLookup.forRealId(realFieldId);
                } catch (Throwable ignored) {
                }
            }

            if (fieldOpt.isPresent()) {
                fieldName = fieldOpt.get().getName();
                fieldType = fieldOpt.get().getType();
            } else {
                fieldName = FIELD_KEY_PREFIX + realFieldId;
                int typeVal = (int) (realFieldId / Constants.CASUAL_FIELD_TYPE_BASE);
                fieldType = FieldType.unmarshall(typeVal);
            }

            Object value = decodeFieldValue(fieldType, fieldData, effectiveCharset);
            multiMap.computeIfAbsent(fieldName, k -> new ArrayList<>()).add(value);
        }

        Map<String, Object> jsonMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : multiMap.entrySet()) {
            List<Object> list = entry.getValue();
            jsonMap.put(entry.getKey(), list.size() == 1 ? list.get(0) : list);
        }
        return jsonMap;
    }

    private static Object decodeFieldValue(FieldType type, byte[] data, Charset charset) {
        ByteBuffer bb = ByteBuffer.wrap(data);
        switch (type) {
            case CASUAL_FIELD_SHORT -> {
                return bb.getShort();
            }
            case CASUAL_FIELD_LONG -> {
                return bb.getLong();
            }
            case CASUAL_FIELD_CHAR -> {
                return String.valueOf((char) Byte.toUnsignedInt(bb.get()));
            }
            case CASUAL_FIELD_FLOAT -> {
                return bb.getFloat();
            }
            case CASUAL_FIELD_DOUBLE -> {
                return bb.getDouble();
            }
            case CASUAL_FIELD_STRING -> {
                int len = data.length;
                while (len > 0 && data[len - 1] == 0) {
                    len--;
                }
                return new String(data, 0, len, charset);
            }
            case CASUAL_FIELD_BINARY -> {
                Object parsedJson = tryParseJsonPayload(data, charset);
                if (parsedJson != null) {
                    return parsedJson;
                }
                return Base64.getEncoder().encodeToString(data);
            }
            default -> {
                return Base64.getEncoder().encodeToString(data);
            }
        }
    }

    /**
     * Wraps payload text back into a {@link ServiceBuffer} based on buffer type.
     * Decodes Base64 for non-JSON buffers, wraps UTF-8 for JSON and CSTRING,
     * or parses JSON object into a binary .FIELDED buffer.
     */
    /**
     * Wraps payload text back into a {@link ServiceBuffer} based on buffer type using UTF-8 default encoding.
     */
    public static ServiceBuffer wrapPayload(String bufferType, String payload) {
        return wrapPayload(bufferType, payload, StandardCharsets.UTF_8);
    }

    /**
     * Wraps payload text back into a {@link ServiceBuffer} based on buffer type and {@link Charset}.
     */
    public static ServiceBuffer wrapPayload(String bufferType, String payload, Charset charset) {
        Charset effectiveCharset = charset != null ? charset : StandardCharsets.UTF_8;
        String type = (bufferType != null && !bufferType.isBlank()) ? bufferType.trim() : "JSON";
        if (isJsonType(type)) {
            String json = (payload != null && !payload.isBlank()) ? payload : "{}";
            if (!StandardCharsets.UTF_8.equals(effectiveCharset)) {
                return ServiceBuffer.of(type, List.of(json.getBytes(effectiveCharset)));
            }
            return ServiceBuffer.of(JsonBuffer.of(json));
        }
        if (payload == null || payload.isBlank()) {
            return ServiceBuffer.of(type, List.of());
        }
        if (isCStringType(type)) {
            String text = payload.endsWith("\0") ? payload : payload + "\0";
            return ServiceBuffer.of("CSTRING/", List.of(text.getBytes(effectiveCharset)));
        }
        if (isFieldedType(type)) {
            String trimmed = payload.trim();
            if (trimmed.startsWith("{")) {
                try {
                    Map<?, ?> jsonMap = OBJECT_MAPPER.readValue(trimmed, Map.class);
                    byte[] encoded = encodeJsonMapToFieldedBuffer(jsonMap, effectiveCharset);
                    return ServiceBuffer.of("CFIELD/", List.of(encoded));
                } catch (Throwable t) {
                    LOG.error(
                            "[CASUAL-FIELDED-ENCODE-FAIL] Failed to encode JSON payload into .FIELDED (FML) buffer: {}",
                            t.getMessage(),
                            t);
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
            return ServiceBuffer.of(type, List.of(payload.getBytes(effectiveCharset)));
        }
    }

    /**
     * Encodes a JSON map into a binary fielded buffer byte array, resolving known fields through
     * CasualFieldedLookup and schema-less FLD_<realId> keys directly from numeric field identifiers.
     */
    private static byte[] encodeJsonMapToFieldedBuffer(Map<?, ?> jsonMap, Charset effectiveCharset) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (Map.Entry<?, ?> entry : jsonMap.entrySet()) {
            String key = String.valueOf(entry.getKey()).trim();
            Object val = entry.getValue();
            if (val == null) {
                continue;
            }

            long realFieldId;
            FieldType fieldType;

            String suffix = key.startsWith(FIELD_KEY_PREFIX) ? key.substring(FIELD_KEY_PREFIX.length()) : "";
            if (!suffix.isEmpty() && isDigitsOnly(suffix)) {
                try {
                    realFieldId = Long.parseLong(suffix);
                    int typeVal = (int) (realFieldId / Constants.CASUAL_FIELD_TYPE_BASE);
                    fieldType = FieldType.unmarshall(typeVal);
                } catch (IllegalArgumentException e) {
                    String sanitized = key.replace('\r', '_').replace('\n', '_');
                    LOG.warn(
                            "[CASUAL-FIELDED-UNKNOWN] Field [{}] has invalid numeric ID or type. Skipping.", sanitized);
                    continue;
                }
            } else if (isFieldTableAvailable()) {
                Optional<CasualField> fieldOpt = CasualFieldedLookup.forName(key);
                if (fieldOpt.isPresent()) {
                    realFieldId = fieldOpt.get().getRealId();
                    fieldType = fieldOpt.get().getType();
                } else {
                    String sanitized = key.replace('\r', '_').replace('\n', '_');
                    LOG.warn(
                            "[CASUAL-FIELDED-UNKNOWN] Field [{}] not found in casual-fields definition table. Skipping.",
                            sanitized);
                    continue;
                }
            } else {
                String sanitized = key.replace('\r', '_').replace('\n', '_');
                LOG.warn(
                        "[CASUAL-FIELDED-UNKNOWN] Field [{}] not recognized as FLD_<id> and no casual-fields definition table is available. Skipping.",
                        sanitized);
                continue;
            }

            if (val instanceof List<?> list) {
                for (Object item : list) {
                    writeFieldItem(baos, realFieldId, fieldType, item, effectiveCharset);
                }
            } else {
                writeFieldItem(baos, realFieldId, fieldType, val, effectiveCharset);
            }
        }
        return baos.toByteArray();
    }

    private static void writeFieldItem(
            ByteArrayOutputStream baos, long realFieldId, FieldType fieldType, Object val, Charset effectiveCharset)
            throws IOException {
        if (val == null) {
            return;
        }
        byte[] valueBytes;
        switch (fieldType) {
            case CASUAL_FIELD_SHORT -> {
                short s = (val instanceof Number n)
                        ? n.shortValue()
                        : Short.parseShort(String.valueOf(val).trim());
                ByteBuffer b = ByteBuffer.allocate(2);
                b.putShort(s);
                valueBytes = b.array();
            }
            case CASUAL_FIELD_LONG -> {
                long l = (val instanceof Number n)
                        ? n.longValue()
                        : Long.parseLong(String.valueOf(val).trim());
                ByteBuffer b = ByteBuffer.allocate(8);
                b.putLong(l);
                valueBytes = b.array();
            }
            case CASUAL_FIELD_CHAR -> {
                char c;
                if (val instanceof Character ch) {
                    c = ch.charValue();
                } else if (val instanceof Number n) {
                    c = (char) n.intValue();
                } else {
                    String str = String.valueOf(val);
                    c = str.isEmpty() ? ' ' : str.charAt(0);
                }
                valueBytes = new byte[] {(byte) c};
            }
            case CASUAL_FIELD_FLOAT -> {
                float f = (val instanceof Number n)
                        ? n.floatValue()
                        : Float.parseFloat(String.valueOf(val).trim());
                ByteBuffer b = ByteBuffer.allocate(4);
                b.putFloat(f);
                valueBytes = b.array();
            }
            case CASUAL_FIELD_DOUBLE -> {
                double d = (val instanceof Number n)
                        ? n.doubleValue()
                        : Double.parseDouble(String.valueOf(val).trim());
                ByteBuffer b = ByteBuffer.allocate(8);
                b.putDouble(d);
                valueBytes = b.array();
            }
            case CASUAL_FIELD_STRING -> {
                String s = (val instanceof Map || val instanceof List)
                        ? OBJECT_MAPPER.writeValueAsString(val)
                        : String.valueOf(val);
                byte[] sBytes = s.getBytes(effectiveCharset);
                valueBytes = new byte[sBytes.length + 1];
                System.arraycopy(sBytes, 0, valueBytes, 0, sBytes.length);
                valueBytes[sBytes.length] = 0;
            }
            case CASUAL_FIELD_BINARY -> {
                if (val instanceof byte[] b) {
                    valueBytes = b;
                } else if (val instanceof String s) {
                    try {
                        valueBytes = Base64.getDecoder().decode(s.trim());
                    } catch (IllegalArgumentException ignored) {
                        valueBytes = s.getBytes(effectiveCharset);
                    }
                } else if (val instanceof Map || val instanceof List) {
                    valueBytes = OBJECT_MAPPER.writeValueAsString(val).getBytes(effectiveCharset);
                } else {
                    valueBytes = String.valueOf(val).getBytes(effectiveCharset);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported field type: " + fieldType);
        }

        ByteBuffer header = ByteBuffer.allocate(16 + valueBytes.length);
        header.putLong(realFieldId);
        header.putLong(valueBytes.length);
        header.put(valueBytes);
        baos.write(header.array());
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
        if (isFieldedType(type)) {
            String str = new String(binaryData, StandardCharsets.UTF_8).trim();
            if (str.startsWith("{")) {
                return wrapPayload(type, str, StandardCharsets.UTF_8);
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(str);
                return ServiceBuffer.of("CFIELD/", List.of(decoded));
            } catch (IllegalArgumentException ignored) {
            }
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

    private static Object tryParseJsonPayload(Object data, Charset charset) {
        if (data == null) {
            return null;
        }
        Charset effectiveCharset = charset != null ? charset : StandardCharsets.UTF_8;
        String str = null;
        if (data instanceof byte[] bytes) {
            if (bytes.length > 0) {
                effectiveCharset = guessCharset(bytes, effectiveCharset);
                str = new String(bytes, effectiveCharset).trim();
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
                Object pseudo = tryParsePseudoJson(str);
                if (pseudo != null) {
                    return pseudo;
                }
            }
        }

        // 2. Base64-encoded JSON or pseudo-JSON payload
        try {
            byte[] decoded = Base64.getDecoder().decode(str);
            Charset b64Charset = guessCharset(decoded, effectiveCharset);
            String decodedStr = new String(decoded, b64Charset).trim();
            if ((decodedStr.startsWith("{") && decodedStr.endsWith("}"))
                    || (decodedStr.startsWith("[") && decodedStr.endsWith("]"))) {
                try {
                    return OBJECT_MAPPER.readValue(decodedStr, Object.class);
                } catch (IOException ignored) {
                    Object pseudo = tryParsePseudoJson(decodedStr);
                    if (pseudo != null) {
                        return pseudo;
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    public static Charset guessCharset(byte[] bytes, Charset defaultCharset) {
        if (bytes == null || bytes.length == 0) {
            return defaultCharset != null ? defaultCharset : StandardCharsets.UTF_8;
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (b <= 0x7F) {
                i++;
            } else if (b >= 0xC2 && b <= 0xDF) {
                if (i + 1 >= bytes.length || (bytes[i + 1] & 0xC0) != 0x80) {
                    return StandardCharsets.ISO_8859_1;
                }
                i += 2;
            } else if (b >= 0xE0 && b <= 0xEF) {
                if (i + 2 >= bytes.length || (bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80) {
                    return StandardCharsets.ISO_8859_1;
                }
                if (b == 0xE0 && (bytes[i + 1] & 0xE0) == 0x80) {
                    return StandardCharsets.ISO_8859_1;
                }
                i += 3;
            } else if (b >= 0xF0 && b <= 0xF4) {
                if (i + 3 >= bytes.length
                        || (bytes[i + 1] & 0xC0) != 0x80
                        || (bytes[i + 2] & 0xC0) != 0x80
                        || (bytes[i + 3] & 0xC0) != 0x80) {
                    return StandardCharsets.ISO_8859_1;
                }
                i += 4;
            } else {
                return StandardCharsets.ISO_8859_1;
            }
        }
        return StandardCharsets.UTF_8;
    }

    public static Object tryParsePseudoJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                return new PseudoJsonParser(trimmed).parse();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static final class PseudoJsonParser {
        private final String text;
        private final int len;
        private int pos;

        PseudoJsonParser(String text) {
            this.text = text;
            this.len = text.length();
            this.pos = 0;
        }

        Object parse() {
            skipWhitespace();
            if (pos >= len) {
                return null;
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            return null;
        }

        private void skipWhitespace() {
            while (pos < len && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= len) {
                return null;
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"' || c == '\'') {
                return parseQuotedString(c);
            }
            return parseLiteral();
        }

        private Map<String, Object> parseObject() {
            pos++; // skip '{'
            Map<String, Object> map = new LinkedHashMap<>();
            while (pos < len) {
                skipWhitespace();
                if (pos >= len || text.charAt(pos) == '}') {
                    if (pos < len) {
                        pos++; // skip '}'
                    }
                    break;
                }
                String key = parseKey();
                skipWhitespace();
                if (pos < len && (text.charAt(pos) == '=' || text.charAt(pos) == ':')) {
                    pos++;
                }
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos < len && (text.charAt(pos) == ',' || text.charAt(pos) == ';')) {
                    pos++;
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            pos++; // skip '['
            List<Object> list = new ArrayList<>();
            while (pos < len) {
                skipWhitespace();
                if (pos >= len || text.charAt(pos) == ']') {
                    if (pos < len) {
                        pos++; // skip ']'
                    }
                    break;
                }
                Object item = parseValue();
                list.add(item);
                skipWhitespace();
                if (pos < len && (text.charAt(pos) == ',' || text.charAt(pos) == ';')) {
                    pos++;
                }
            }
            return list;
        }

        private String parseKey() {
            skipWhitespace();
            if (pos < len && (text.charAt(pos) == '"' || text.charAt(pos) == '\'')) {
                return parseQuotedString(text.charAt(pos));
            }
            int start = pos;
            while (pos < len) {
                char c = text.charAt(pos);
                if (c == '=' || c == ':' || c == '}' || c == ',' || c == ';' || Character.isWhitespace(c)) {
                    break;
                }
                pos++;
            }
            return text.substring(start, pos).trim();
        }

        private String parseQuotedString(char quote) {
            pos++; // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = text.charAt(pos++);
                if (c == '\\' && pos < len) {
                    sb.append(text.charAt(pos++));
                } else if (c == quote) {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseLiteral() {
            int start = pos;
            while (pos < len) {
                char c = text.charAt(pos);
                if (c == ',' || c == ';' || c == '}' || c == ']' || c == '=' || c == ':') {
                    break;
                }
                pos++;
            }
            String raw = text.substring(start, pos).trim();
            if ("null".equalsIgnoreCase(raw)) {
                return null;
            }
            if ("true".equalsIgnoreCase(raw)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(raw)) {
                return Boolean.FALSE;
            }
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
            }
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
            }
            return raw;
        }
    }

    private static boolean isDigitsOnly(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

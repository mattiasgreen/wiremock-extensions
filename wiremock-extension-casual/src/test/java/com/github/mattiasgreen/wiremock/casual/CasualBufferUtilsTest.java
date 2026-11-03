package com.github.mattiasgreen.wiremock.casual;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;

class CasualBufferUtilsTest {

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("CASUAL_FIELD_TABLE");
        se.laz.casual.config.ConfigurationService.setConfiguration(
                se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
    }

    @Test
    @DisplayName("Should accurately identify JSON buffer types")
    void shouldIdentifyJsonBufferTypes() {
        assertThat(CasualBufferUtils.isJsonType("JSON")).isTrue();
        assertThat(CasualBufferUtils.isJsonType("json")).isTrue();
        assertThat(CasualBufferUtils.isJsonType(".json/")).isTrue();
        assertThat(CasualBufferUtils.isJsonType(".json/jscd")).isTrue();
        assertThat(CasualBufferUtils.isJsonType(null)).isTrue();
        assertThat(CasualBufferUtils.isJsonType("")).isTrue();

        assertThat(CasualBufferUtils.isJsonType("CFIELD/")).isFalse();
        assertThat(CasualBufferUtils.isJsonType("FIELDED")).isFalse();
        assertThat(CasualBufferUtils.isJsonType("X_OCTET")).isFalse();
        assertThat(CasualBufferUtils.isJsonType("CSTRING/")).isFalse();
    }

    @Test
    @DisplayName("Should extract UTF-8 string for JSON buffer")
    void shouldExtractStringForJsonBuffer() {
        String json = "{\"hello\":\"world\"}";
        ServiceBuffer buffer = ServiceBuffer.of(JsonBuffer.of(json));

        String extracted = CasualBufferUtils.extractPayload(buffer);
        assertThat(extracted).isEqualTo(json);
    }

    @Test
    @DisplayName("Should extract Base64 string for binary non-JSON buffer")
    void shouldExtractBase64ForBinaryBuffer() {
        byte[] rawBytes = new byte[] {0x00, 0x01, (byte) 0xFF, 0x42};
        ServiceBuffer buffer = ServiceBuffer.of("CFIELD/", List.of(rawBytes));

        String extracted = CasualBufferUtils.extractPayload(buffer);
        String expectedBase64 = Base64.getEncoder().encodeToString(rawBytes);

        assertThat(extracted).isEqualTo(expectedBase64);
    }

    @Test
    @DisplayName("Should wrap Base64 payload back into binary ServiceBuffer")
    void shouldWrapBase64PayloadIntoBinaryBuffer() {
        byte[] original = new byte[] {0x10, 0x20, 0x30, 0x40};
        String base64 = Base64.getEncoder().encodeToString(original);

        ServiceBuffer wrapped = CasualBufferUtils.wrapPayload("CFIELD/", base64);
        assertThat(wrapped.getType()).isEqualTo("CFIELD/");

        byte[] recovered = CasualBufferUtils.toByteArray(wrapped);
        assertThat(recovered).isEqualTo(original);
    }

    @Test
    @DisplayName("Should wrap JSON text back into JsonBuffer")
    void shouldWrapJsonPayloadIntoJsonBuffer() {
        String json = "{\"key\":\"value\"}";
        ServiceBuffer wrapped = CasualBufferUtils.wrapPayload(".json/", json);

        assertThat(wrapped.getType()).isEqualTo(se.laz.casual.api.buffer.CasualBufferType.JSON.getName());
        assertThat(CasualBufferUtils.extractPayload(wrapped)).isEqualTo(json);
    }

    @Test
    @DisplayName("Should extract and wrap CSTRING buffer stripping and adding null terminator")
    void shouldExtractAndWrapCStringBuffer() {
        String originalText = "Hello Casual CSTRING";
        ServiceBuffer buffer = ServiceBuffer.of(
                "CSTRING/", List.of((originalText + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        String extracted = CasualBufferUtils.extractPayload(buffer);
        assertThat(extracted).isEqualTo(originalText);
        assertThat(extracted).doesNotEndWith("\0");

        ServiceBuffer wrapped = CasualBufferUtils.wrapPayload("CSTRING/", extracted);
        assertThat(wrapped.getType()).isEqualTo("CSTRING/");
        String recoveredRaw =
                new String(CasualBufferUtils.toByteArray(wrapped), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(recoveredRaw).isEqualTo(originalText + "\0");
    }

    @Test
    @DisplayName("Should produce clean hex dumps for TRACE logging")
    void shouldProduceCleanHexDump() {
        byte[] bytes = new byte[] {0x01, 0x02, (byte) 0xAA, (byte) 0xBB};
        String dump = CasualBufferUtils.toHexDump(bytes, 16);

        assertThat(dump).contains("01 02 AA BB");
    }

    @Test
    @DisplayName("Should decode to schema-less JSON when field table is not available and re-encode to binary")
    void shouldDecodeToSchemaLessJsonWhenFieldTableNotAvailable() {
        // Construct a realistic 18-byte buffer with 8 bytes field id (short type), 8 bytes size, and 2 bytes short
        // value
        long realId = (1L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE)
                + 1L; // type 1 (SHORT), id 1
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(18);
        bb.putLong(realId);
        bb.putLong(2L);
        bb.putShort((short) 4128);
        byte[] realisticFielded = bb.array();
        ServiceBuffer buffer = ServiceBuffer.of(".FIELDED", List.of(realisticFielded));

        // When casual-fields.json is not in classpath and env is not set/not found
        assertThat(CasualBufferUtils.isFieldTableAvailable()).isFalse();

        // Must decode to schema-less JSON with FLD_<realId>
        String extracted = CasualBufferUtils.extractPayload(buffer);
        assertThat(extracted).isNotNull();
        assertThat(extracted).isEqualTo("{\"FLD_" + realId + "\":4128}");

        // And wrapPayload must re-encode back to the exact binary wire buffer
        ServiceBuffer wrapped = CasualBufferUtils.wrapPayload(".FIELDED", extracted);
        assertThat(wrapped.getType()).isEqualTo("CFIELD/");
        assertThat(CasualBufferUtils.toByteArray(wrapped)).isEqualTo(realisticFielded);
    }

    @Test
    @DisplayName("Should fall back to Base64 when fielded buffer payload is corrupt or truncated")
    void shouldFallBackToBase64WhenFieldedBufferIsCorruptOrTruncated() {
        byte[] corruptFielded = new byte[] {0x01, 0x02, 0x03, 0x04};
        ServiceBuffer buffer = ServiceBuffer.of(".FIELDED", List.of(corruptFielded));

        String extracted = CasualBufferUtils.extractPayload(buffer);
        assertThat(extracted).isEqualTo(Base64.getEncoder().encodeToString(corruptFielded));
    }

    @Test
    @DisplayName(
            "Should decode schema-less fielded buffer with strings, longs, shorts, and embedded JSON in binary fields")
    void shouldDecodeSchemaLessFieldedBufferWithEmbeddedJson() {
        // Construct multi-field schema-less buffer with generic anonymized identifiers:
        // Field 1: string (type 6)
        long field1Id = (6L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 1929L;
        byte[] strBytes = "sampleClientHost\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Field 2: long (type 2)
        long field2Id = (2L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 1500L;

        // Field 3: binary with embedded JSON (type 7)
        long field3Id = (7L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 2010L;
        byte[] jsonBytes = "{\"subEntityId\":\"sample-12345\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Field 4: short (type 1)
        long field4Id = (1L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 1005L;

        java.nio.ByteBuffer bb =
                java.nio.ByteBuffer.allocate(16 + strBytes.length + 16 + 8 + 16 + jsonBytes.length + 16 + 2);
        // Field 1
        bb.putLong(field1Id);
        bb.putLong(strBytes.length);
        bb.put(strBytes);
        // Field 2
        bb.putLong(field2Id);
        bb.putLong(8L);
        bb.putLong(1001L);
        // Field 3
        bb.putLong(field3Id);
        bb.putLong(jsonBytes.length);
        bb.put(jsonBytes);
        // Field 4
        bb.putLong(field4Id);
        bb.putLong(2L);
        bb.putShort((short) 5);

        ServiceBuffer buffer = ServiceBuffer.of("CFIELD/", List.of(bb.array()));
        String extracted = CasualBufferUtils.extractPayload(buffer);

        assertThat(extracted).contains("\"FLD_" + field1Id + "\":\"sampleClientHost\"");
        assertThat(extracted).contains("\"FLD_" + field2Id + "\":1001");
        assertThat(extracted).contains("\"FLD_" + field3Id + "\":{\"subEntityId\":\"sample-12345\"}");
        assertThat(extracted).contains("\"FLD_" + field4Id + "\":5");

        // Roundtrip wrap back to binary buffer
        ServiceBuffer wrapped = CasualBufferUtils.wrapPayload("CFIELD/", extracted);
        assertThat(wrapped.getType()).isEqualTo("CFIELD/");
        assertThat(CasualBufferUtils.toByteArray(wrapped)).isEqualTo(bb.array());
    }

    @Test
    @DisplayName("Should detect field table availability accurately for existing vs non-existent files")
    void shouldDetectFieldTableAvailabilityAccurately() throws Exception {
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("test-fields", ".json");
        temp.toFile().deleteOnExit();
        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isTrue();

            System.setProperty("CASUAL_FIELD_TABLE", "/non/existent/path/casual-fields.json");
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isFalse();
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
        }
    }

    @Test
    @DisplayName("Should load JsonProvider SPI implementation from casual-json-provider-gson")
    void shouldLoadJsonProviderSpi() {
        se.laz.casual.api.external.json.JsonProvider provider =
                se.laz.casual.api.external.json.JsonProviderFactory.getJsonProvider();
        assertThat(provider).isNotNull();
    }

    @Test
    @DisplayName("Should encode and decode fielded calls with structured JSON when field table is configured")
    void shouldEncodeAndDecodeFieldedCallsWithConfiguredTable() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                { "name": "FLD_USER_ID", "id": 1, "type": "long" },
                { "name": "FLD_USER_NAME", "id": 2, "type": "string" },
                { "name": "FLD_CRM_CUSTOMER_ID", "id": 3, "type": "long" },
                { "name": "FLD_CRM_CUSTOMER_NAME", "id": 4, "type": "string" },
                { "name": "FLD_CRM_STATUS_FLAG", "id": 5, "type": "short" },
                { "name": "FLD_BILLING_AMOUNT", "id": 6, "type": "double" },
                { "name": "FLD_BILLING_DISCOUNT", "id": 7, "type": "float" },
                { "name": "FLD_BILLING_CURRENCY_CODE", "id": 8, "type": "char" },
                { "name": "FLD_ARCHIVE_DATA", "id": 9, "type": "binary" }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, java.nio.charset.StandardCharsets.UTF_8);

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isTrue();

            // Create and encode a real fielded buffer using FieldedTypeBuffer
            se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer fml =
                    se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer.create();
            fml.write("FLD_USER_NAME", "Alice");
            fml.write("FLD_USER_ID", 1001L);

            List<byte[]> encodedBytes = fml.encode();
            ServiceBuffer buffer = ServiceBuffer.of(".FIELDED", encodedBytes);

            // Extract to human-readable JSON
            String jsonPayload = CasualBufferUtils.extractPayload(buffer);
            assertThat(jsonPayload).isNotNull();
            assertThat(jsonPayload).contains("\"FLD_USER_NAME\":\"Alice\"");
            assertThat(jsonPayload).contains("\"FLD_USER_ID\":1001");

            // Wrap JSON back into .FIELDED buffer
            ServiceBuffer wrappedBuffer = CasualBufferUtils.wrapPayload(".FIELDED", jsonPayload);
            assertThat(wrappedBuffer.getType()).isEqualTo("CFIELD/");

            // Decode wrapped buffer again to verify roundtrip fidelity
            String reExtracted = CasualBufferUtils.extractPayload(wrappedBuffer);
            assertThat(reExtracted).contains("\"FLD_USER_NAME\":\"Alice\"");
            assertThat(reExtracted).contains("\"FLD_USER_ID\":1001");
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
        }
    }

    @Test
    @DisplayName("Should encode fielded buffer with binary, numeric, and string fields from generic JSON")
    void shouldEncodeFieldedBufferWithBinaryAndNumericFieldsFromGenericJson() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                { "name": "FLD_USER_ID", "id": 1, "type": "long" },
                { "name": "FLD_USER_NAME", "id": 2, "type": "string" },
                { "name": "FLD_CRM_CUSTOMER_ID", "id": 3, "type": "long" },
                { "name": "FLD_CRM_CUSTOMER_NAME", "id": 4, "type": "string" },
                { "name": "FLD_CRM_STATUS_FLAG", "id": 5, "type": "short" },
                { "name": "FLD_BILLING_AMOUNT", "id": 6, "type": "double" },
                { "name": "FLD_BILLING_DISCOUNT", "id": 7, "type": "float" },
                { "name": "FLD_BILLING_CURRENCY_CODE", "id": 8, "type": "char" },
                { "name": "FLD_ARCHIVE_DATA", "id": 9, "type": "binary" }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-crm-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, java.nio.charset.StandardCharsets.UTF_8);

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isTrue();

            String binaryPayloadB64 = Base64.getEncoder()
                    .encodeToString("{\"caseId\":\"12356\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String jsonInput = String.format(
                    java.util.Locale.ROOT,
                    """
                    {
                      "FLD_CRM_CUSTOMER_ID": 54321,
                      "FLD_CRM_CUSTOMER_NAME": "Acme Partner",
                      "FLD_CRM_STATUS_FLAG": 1,
                      "FLD_BILLING_AMOUNT": 199.95,
                      "FLD_BILLING_DISCOUNT": 0.15,
                      "FLD_BILLING_CURRENCY_CODE": "E",
                      "FLD_ARCHIVE_DATA": "%s",
                      "UNKNOWN_EXTRA_FIELD": "ignored_gracefully"
                    }
                    """,
                    binaryPayloadB64);

            ServiceBuffer wrappedBuffer = CasualBufferUtils.wrapPayload(".FIELDED", jsonInput);
            assertThat(wrappedBuffer.getType()).isEqualTo("CFIELD/");

            // Must NOT return raw JSON string bytes in a CFIELD buffer
            byte[] rawBufferBytes = CasualBufferUtils.toByteArray(wrappedBuffer);
            String asAscii = new String(rawBufferBytes, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(asAscii).doesNotStartWith("{");

            // Must decode cleanly via Casual's FieldedTypeBufferDecoder without throwing "field with real id does not
            // exist"
            se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer decoded =
                    se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer.create(wrappedBuffer.getBytes());
            assertThat(decoded.read("FLD_CRM_CUSTOMER_ID").getData()).isEqualTo(54321L);
            assertThat(decoded.read("FLD_CRM_CUSTOMER_NAME").getData()).isEqualTo("Acme Partner");
            assertThat(decoded.read("FLD_CRM_STATUS_FLAG").getData()).isEqualTo((short) 1);
            assertThat(decoded.read("FLD_BILLING_CURRENCY_CODE").getData()).isEqualTo('E');

            byte[] decodedBinary = (byte[]) decoded.read("FLD_ARCHIVE_DATA").getData();
            assertThat(new String(decodedBinary, java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("{\"caseId\":\"12356\"}");

            // Extract back to JSON and verify round-trip
            String reExtracted = CasualBufferUtils.extractPayload(wrappedBuffer);
            assertThat(reExtracted).contains("\"FLD_CRM_CUSTOMER_ID\":54321");
            assertThat(reExtracted).contains("\"FLD_CRM_CUSTOMER_NAME\":\"Acme Partner\"");
            assertThat(reExtracted).contains("\"FLD_CRM_STATUS_FLAG\":1");
            assertThat(reExtracted).contains("\"FLD_ARCHIVE_DATA\":{\"caseId\":\"12356\"}");
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
        }
    }

    @Test
    @DisplayName("Should parse hex strings accurately into byte arrays")
    void shouldParseHexStrings() {
        byte[] expected = new byte[] {0x01, 0x02, (byte) 0xAA, (byte) 0xBB};
        assertThat(CasualBufferUtils.fromHex("01 02 AA BB")).isEqualTo(expected);
        assertThat(CasualBufferUtils.fromHex("0102AABB")).isEqualTo(expected);
        assertThat(CasualBufferUtils.fromHex("0x01, 0x02, 0xaa, 0xbb")).isEqualTo(expected);
        assertThat(CasualBufferUtils.fromHex(null)).isEmpty();
        assertThat(CasualBufferUtils.fromHex("")).isEmpty();
    }

    @Test
    @DisplayName("Should produce canonical hex dump with offset and ASCII characters")
    void shouldProduceCanonicalHexDump() {
        byte[] bytes = "Hello, World!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String dump = CasualBufferUtils.toCanonicalHexDump(bytes, 32);

        assertThat(dump).contains("00000000");
        assertThat(dump).contains("48 65 6C 6C 6F");
        assertThat(dump).contains("|Hello, World!|");
    }
}

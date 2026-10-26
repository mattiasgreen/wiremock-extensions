package se.laz.casual.api.buffer;

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
    @DisplayName("Should early exit to Base64 when field table is not available")
    void shouldEarlyExitToBase64WhenFieldTableNotAvailable() {
        // Construct a realistic 16-byte buffer with 8 bytes field id and 8 bytes length
        byte[] realisticFielded = new byte[] {
            0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x01, // 8-byte field ID
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, // 8-byte size
            0x10, 0x20, 0x30, 0x40 // payload data
        };
        ServiceBuffer buffer = ServiceBuffer.of(".FIELDED", List.of(realisticFielded));

        // When casual-fields.json is not in classpath and env is not set/not found
        assertThat(CasualBufferUtils.isFieldTableAvailable()).isFalse();

        // Must early exit to Base64 and not throw ExceptionInInitializerError
        String extracted = CasualBufferUtils.extractPayload(buffer);
        assertThat(extracted).isNotNull();
        assertThat(extracted).isEqualTo(Base64.getEncoder().encodeToString(realisticFielded));

        // And wrapPayload must early exit to Base64 decoding
        ServiceBuffer wrapped = CasualBufferUtils.wrapPayload(".FIELDED", extracted);
        assertThat(CasualBufferUtils.toByteArray(wrapped)).isEqualTo(realisticFielded);
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
                {
                  "name": "FLD_USER_ID",
                  "id": 1,
                  "type": "long"
                },
                {
                  "name": "FLD_USER_NAME",
                  "id": 2,
                  "type": "string"
                }
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
}

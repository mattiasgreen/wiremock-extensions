package se.laz.casual.api.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;

class CasualBufferUtilsTest {

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
    @DisplayName("Should produce clean hex dumps for TRACE logging")
    void shouldProduceCleanHexDump() {
        byte[] bytes = new byte[] {0x01, 0x02, (byte) 0xAA, (byte) 0xBB};
        String dump = CasualBufferUtils.toHexDump(bytes, 16);

        assertThat(dump).contains("01 02 AA BB");
    }
}

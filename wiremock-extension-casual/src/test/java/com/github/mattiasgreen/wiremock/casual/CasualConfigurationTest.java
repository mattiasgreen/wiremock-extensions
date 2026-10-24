package com.github.mattiasgreen.wiremock.casual;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CasualConfigurationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        System.clearProperty(CasualConfiguration.PROP_ENABLED);
        System.clearProperty(CasualConfiguration.PROP_PORT);
    }

    @Test
    void testDefaults() {
        CasualConfiguration config = CasualConfiguration.fromEnvironment();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getPort()).isEqualTo(7772);
    }

    @Test
    void testSystemPropertiesPrecedence() {
        System.setProperty(CasualConfiguration.PROP_ENABLED, "false");
        System.setProperty(CasualConfiguration.PROP_PORT, "8888");

        CasualConfiguration config = CasualConfiguration.fromEnvironment();

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getPort()).isEqualTo(8888);
    }

    @Test
    void testJsonSerializationAndDeserialization() throws Exception {
        CasualConfiguration original = new CasualConfiguration();
        original.setEnabled(true);
        original.setPort(7000);

        String json = mapper.writeValueAsString(original.toMap());
        CasualConfiguration deserialized = mapper.readValue(json, CasualConfiguration.class);

        assertThat(deserialized.isEnabled()).isTrue();
        assertThat(deserialized.getPort()).isEqualTo(7000);
    }
}

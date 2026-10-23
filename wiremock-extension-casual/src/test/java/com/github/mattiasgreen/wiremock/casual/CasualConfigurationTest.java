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
        System.clearProperty(CasualConfiguration.PROP_UPSTREAM_HOST);
        System.clearProperty(CasualConfiguration.PROP_UPSTREAM_PORT);
        System.clearProperty(CasualConfiguration.PROP_UPSTREAM_ENABLED);
        System.clearProperty(CasualConfiguration.PROP_RECORD_STUBS);
        System.clearProperty(CasualConfiguration.PROP_RECORD);
        System.clearProperty(CasualConfiguration.PROP_RECORD_FAULTS);
        System.clearProperty(CasualConfiguration.PROP_RECORD_NOT_FOUND);
    }

    @Test
    void testDefaults() {
        CasualConfiguration config = CasualConfiguration.fromEnvironment();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getPort()).isEqualTo(7772);
        assertThat(config.getUpstreamHost()).isNull();
        assertThat(config.getUpstreamPort()).isEqualTo(0);
        assertThat(config.hasUpstreamConfigured()).isFalse();
        assertThat(config.isUpstreamEnabled()).isFalse();
        assertThat(config.isRecordStubs()).isFalse();
        assertThat(config.isRecordFaults()).isTrue();
        assertThat(config.isRecordNotFound()).isFalse();
    }

    @Test
    void testSystemPropertiesPrecedenceAndUpstreamAutoEnabled() {
        System.setProperty(CasualConfiguration.PROP_ENABLED, "true");
        System.setProperty(CasualConfiguration.PROP_PORT, "8888");
        System.setProperty(CasualConfiguration.PROP_UPSTREAM_HOST, "upstream.example.com");
        System.setProperty(CasualConfiguration.PROP_UPSTREAM_PORT, "9999");
        System.setProperty(CasualConfiguration.PROP_RECORD_STUBS, "true");

        CasualConfiguration config = CasualConfiguration.fromEnvironment();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getPort()).isEqualTo(8888);
        assertThat(config.getUpstreamHost()).isEqualTo("upstream.example.com");
        assertThat(config.getUpstreamPort()).isEqualTo(9999);
        assertThat(config.hasUpstreamConfigured()).isTrue();
        // Upstream is auto-enabled when host and port are configured
        assertThat(config.isUpstreamEnabled()).isTrue();
        assertThat(config.isRecordStubs()).isTrue();
    }

    @Test
    void testExplicitUpstreamDisabled() {
        System.setProperty(CasualConfiguration.PROP_UPSTREAM_HOST, "upstream.example.com");
        System.setProperty(CasualConfiguration.PROP_UPSTREAM_PORT, "9999");
        System.setProperty(CasualConfiguration.PROP_UPSTREAM_ENABLED, "false");

        CasualConfiguration config = CasualConfiguration.fromEnvironment();

        assertThat(config.hasUpstreamConfigured()).isTrue();
        assertThat(config.isUpstreamEnabled()).isFalse();
    }

    @Test
    void testJsonSerializationAndDeserialization() throws Exception {
        CasualConfiguration original = new CasualConfiguration();
        original.setUpstreamHost("localhost");
        original.setUpstreamPort(7000);
        original.setRecordStubs(true);

        String json = mapper.writeValueAsString(original.toMap());
        CasualConfiguration deserialized = mapper.readValue(json, CasualConfiguration.class);

        assertThat(deserialized.getUpstreamHost()).isEqualTo("localhost");
        assertThat(deserialized.getUpstreamPort()).isEqualTo(7000);
        assertThat(deserialized.isUpstreamEnabled()).isTrue();
        assertThat(deserialized.isRecordStubs()).isTrue();
    }
}

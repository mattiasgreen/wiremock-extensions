package com.github.mattiasgreen.wiremock.casual;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.flags.Flag;
import se.laz.casual.config.ConfigurationOptions;
import se.laz.casual.config.ConfigurationService;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;

/**
 * Initializes global {@link ConfigurationService} defaults for in-process Java SE operation
 * and legacy WildFly ({@code casual-java <= 3.4.4}) 8-byte trailing padding interoperability.
 */
final class CasualProtocolConfig {

    static final String STRICT_VALIDATION_KEY = "CASUAL_NETWORK_PROTOCOL_STRICT_VALIDATION";

    static {
        ConfigurationService.setConfiguration(ConfigurationOptions.CASUAL_OUTBOUND_UNMANAGED, true);
        ConfigurationService.setConfiguration(ConfigurationOptions.CASUAL_UNMANAGED, true);
        configureStrictValidationDefault();
    }

    private CasualProtocolConfig() {}

    static void ensureInitialized() {
        // Triggers static initializer once per JVM
    }

    static void configureStrictValidationDefault() {
        String sysProp = System.getProperty(STRICT_VALIDATION_KEY);
        if (sysProp != null && !sysProp.isBlank()) {
            ConfigurationService.setConfiguration(
                    ConfigurationOptions.CASUAL_NETWORK_PROTOCOL_STRICT_VALIDATION,
                    Boolean.parseBoolean(sysProp.trim()));
            return;
        }
        String envVal = System.getenv(STRICT_VALIDATION_KEY);
        String configFile = System.getenv("CASUAL_CONFIG_FILE");
        if ((envVal == null || envVal.isBlank()) && (configFile == null || configFile.isBlank())) {
            ConfigurationService.setConfiguration(
                    ConfigurationOptions.CASUAL_NETWORK_PROTOCOL_STRICT_VALIDATION, false);
        }
    }

    /**
     * Reads XATMI flags from a {@link CasualServiceCallRequestMessage} without throwing if an
     * in-memory test builder left {@code xatmiFlags} unset (since upstream {@code Flag.of(null)} throws NPE).
     */
    @SuppressFBWarnings(
            value = "DCN_NULLPOINTER_EXCEPTION",
            justification =
                    "Upstream CasualServiceCallRequestMessage.getXatmiFlags() throws NPE when xatmiFlags is unset")
    static Flag<AtmiFlags> safeXatmiFlags(CasualServiceCallRequestMessage req) {
        if (req == null) {
            return Flag.of(AtmiFlags.NOFLAG);
        }
        try {
            Flag<AtmiFlags> flags = req.getXatmiFlags();
            return flags != null ? flags : Flag.of(AtmiFlags.NOFLAG);
        } catch (NullPointerException e) {
            return Flag.of(AtmiFlags.NOFLAG);
        }
    }
}

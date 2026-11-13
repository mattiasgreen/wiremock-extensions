package com.github.mattiasgreen.wiremock.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Manages runtime inspection, dynamic updates, and startup environment configuration of Logback logger levels.
 */
public final class LogbackLevelManager {

    public static final String PROP_LOG_LEVEL = "log.level";
    public static final String PROP_WIREMOCK_LOG_LEVEL = "wiremock.log.level";
    public static final String ENV_LOG_LEVEL = "LOG_LEVEL";

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(LogbackLevelManager.class);

    private static final Set<String> UNSET_TOKENS = Set.of("INHERITED", "NULL", "DEFAULT");
    private static final Map<String, Level> VALID_LEVELS = Map.of(
            "TRACE", Level.TRACE,
            "DEBUG", Level.DEBUG,
            "INFO", Level.INFO,
            "WARN", Level.WARN,
            "ERROR", Level.ERROR,
            "OFF", Level.OFF);

    private static final Map<String, Level> BASELINE_LEVELS = captureCurrentLevels();

    private final Function<String, String> envProvider;
    private volatile Map<String, Level> startupLevels;

    public LogbackLevelManager() {
        this(System::getenv);
    }

    public LogbackLevelManager(Function<String, String> envProvider) {
        this.envProvider = Objects.requireNonNull(envProvider, "envProvider must not be null");
        initializeFromEnvironment();
    }

    /**
     * Applies startup log level overrides from {@code -Dlog.level}, {@code -Dwiremock.log.level},
     * or {@code LOG_LEVEL} on top of the current Logback configuration and snapshots the startup state.
     */
    public synchronized void initializeFromEnvironment() {
        String startupSpec = resolveStartupSpec();
        if (startupSpec != null && !startupSpec.isBlank()) {
            applyStartupLevels(startupSpec);
        }
        this.startupLevels = captureCurrentLevels();
    }

    /**
     * Atomically validates and applies a comma-separated {@code logger=LEVEL} specification.
     *
     * @param spec comma-separated {@code logger=LEVEL} string (e.g. {@code "com.github.mattiasgreen=TRACE,se.laz.casual=TRACE"})
     * @return formatted string of all currently configured logger levels
     * @throws IllegalArgumentException if the specification is blank or contains any invalid entry
     */
    public synchronized String applyLevels(String spec) {
        String normalized = stripQuotes(spec);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("Log level specification must not be empty");
        }

        String[] tokens = normalized.split(",");
        List<ParsedDirective> directives = new ArrayList<>(tokens.length);
        for (String rawToken : tokens) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Log level specification contains an empty entry");
            }
            directives.add(parseDirective(token));
        }

        if (directives.isEmpty()) {
            throw new IllegalArgumentException("Log level specification must contain at least one logger=LEVEL entry");
        }

        LoggerContext context = getLoggerContext();
        if (context != null) {
            for (ParsedDirective directive : directives) {
                Logger logger = context.getLogger(directive.loggerName());
                logger.setLevel(directive.level());
            }
            LOG.info("Updated Logback log levels: {}", sanitize(normalized));
        }

        return formatConfiguredLevels();
    }

    /**
     * Best-effort application of startup log level overrides. Invalid entries are logged as warnings
     * without aborting startup or skipping valid entries.
     */
    void applyStartupLevels(String spec) {
        String normalized = stripQuotes(spec);
        if (normalized == null || normalized.isBlank()) {
            return;
        }

        LoggerContext context = getLoggerContext();
        if (context == null) {
            return;
        }

        String[] tokens = normalized.split(",");
        for (String rawToken : tokens) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ParsedDirective directive = parseDirective(token);
                context.getLogger(directive.loggerName()).setLevel(directive.level());
            } catch (IllegalArgumentException e) {
                LOG.warn(
                        "Ignoring invalid startup LOG_LEVEL entry '{}': {}", sanitize(token), sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Resets all Logback logger levels back to the initial startup snapshot (including any {@code LOG_LEVEL} overrides).
     *
     * @return formatted string of all configured logger levels after reset
     */
    public synchronized String reset() {
        restoreSnapshot(this.startupLevels);
        LOG.info("Reset Logback log levels to startup state");
        return formatConfiguredLevels();
    }

    /**
     * Restores Logback logger levels to the original baseline captured before any runtime or startup overrides.
     */
    public synchronized void restoreBaseline() {
        restoreSnapshot(BASELINE_LEVELS);
        this.startupLevels = new LinkedHashMap<>(BASELINE_LEVELS);
    }

    /**
     * Formats all loggers with an explicitly configured level as a comma-separated {@code logger=LEVEL} string,
     * with {@code ROOT} first followed by remaining configured loggers in alphabetical order.
     */
    public synchronized String formatConfiguredLevels() {
        LoggerContext context = getLoggerContext();
        if (context == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level rootLevel = root.getLevel() != null ? root.getLevel() : root.getEffectiveLevel();
        sb.append(org.slf4j.Logger.ROOT_LOGGER_NAME).append('=').append(rootLevel);

        Map<String, Level> configured = new TreeMap<>(Comparator.naturalOrder());
        for (Logger logger : context.getLoggerList()) {
            if (!org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName()) && logger.getLevel() != null) {
                configured.put(logger.getName(), logger.getLevel());
            }
        }

        for (Map.Entry<String, Level> entry : configured.entrySet()) {
            sb.append(',').append(entry.getKey()).append('=').append(entry.getValue());
        }

        return sb.toString();
    }

    private String resolveStartupSpec() {
        String prop = System.getProperty(PROP_LOG_LEVEL);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String wmProp = System.getProperty(PROP_WIREMOCK_LOG_LEVEL);
        if (wmProp != null && !wmProp.isBlank()) {
            return wmProp.trim();
        }
        String env = envProvider.apply(ENV_LOG_LEVEL);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return null;
    }

    private static ParsedDirective parseDirective(String token) {
        int eqIdx = token.indexOf('=');
        if (eqIdx <= 0 || eqIdx == token.length() - 1 || token.indexOf('=', eqIdx + 1) != -1) {
            throw new IllegalArgumentException("Invalid entry '" + token + "' (expected format: logger.name=LEVEL)");
        }

        String rawLogger = token.substring(0, eqIdx).trim();
        String rawLevel = token.substring(eqIdx + 1).trim();
        if (rawLogger.isEmpty() || rawLevel.isEmpty() || containsWhitespace(rawLogger)) {
            throw new IllegalArgumentException("Invalid entry '" + token + "' (expected format: logger.name=LEVEL)");
        }

        String loggerName = org.slf4j.Logger.ROOT_LOGGER_NAME.equalsIgnoreCase(rawLogger)
                ? org.slf4j.Logger.ROOT_LOGGER_NAME
                : rawLogger;
        String upperLevel = rawLevel.toUpperCase(Locale.ROOT);

        if (UNSET_TOKENS.contains(upperLevel)) {
            if (org.slf4j.Logger.ROOT_LOGGER_NAME.equals(loggerName)) {
                throw new IllegalArgumentException("ROOT logger level cannot be set to " + upperLevel);
            }
            return new ParsedDirective(loggerName, null);
        }

        Level level = VALID_LEVELS.get(upperLevel);
        if (level == null) {
            throw new IllegalArgumentException("Unsupported log level '" + rawLevel + "' for logger '" + loggerName
                    + "' (allowed: TRACE, DEBUG, INFO, WARN, ERROR, OFF, INHERITED, NULL)");
        }

        return new ParsedDirective(loggerName, level);
    }

    private static void restoreSnapshot(Map<String, Level> snapshot) {
        LoggerContext context = getLoggerContext();
        if (context == null || snapshot == null) {
            return;
        }
        for (Logger logger : context.getLoggerList()) {
            String name = logger.getName();
            if (snapshot.containsKey(name)) {
                logger.setLevel(snapshot.get(name));
            } else if (!org.slf4j.Logger.ROOT_LOGGER_NAME.equals(name)) {
                logger.setLevel(null);
            }
        }
        for (Map.Entry<String, Level> entry : snapshot.entrySet()) {
            context.getLogger(entry.getKey()).setLevel(entry.getValue());
        }
    }

    private static Map<String, Level> captureCurrentLevels() {
        LoggerContext context = getLoggerContext();
        if (context == null) {
            return Map.of();
        }
        Map<String, Level> map = new LinkedHashMap<>();
        for (Logger logger : context.getLoggerList()) {
            if (logger.getLevel() != null) {
                map.put(logger.getName(), logger.getLevel());
            } else if (org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                map.put(logger.getName(), logger.getEffectiveLevel());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static LoggerContext getLoggerContext() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext loggerContext) {
            return loggerContext;
        }
        return null;
    }

    private static String stripQuotes(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                        || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static boolean containsWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        return input.replace('\r', ' ').replace('\n', ' ');
    }

    private record ParsedDirective(String loggerName, Level level) {}
}

package io.jacoco.otel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Agent configuration.
 *
 * <p>There are two factory methods matching the two deployment modes:
 * <ul>
 *   <li>{@link #parse(String)} — standalone mode; reads agent args, config file, then env vars.</li>
 *   <li>{@link #forExtensionMode()} — extension mode; reads env vars only (no agent args exist
 *       when the jar is loaded as an OTel extension rather than a {@code -javaagent:}).</li>
 * </ul>
 *
 * <p>Precedence for standalone mode (highest to lowest):
 * <ol>
 *   <li>Environment variables ({@code JACOCO_OTEL_*})</li>
 *   <li>Agent argument inline key=value pairs</li>
 *   <li>Properties file referenced by {@code config=<path>}</li>
 *   <li>Built-in defaults</li>
 * </ol>
 */
public class AgentConfig {

    private static final int    DEFAULT_INTERVAL_SECONDS     = 60;
    private static final int    DEFAULT_GRACE_PERIOD_SECONDS = 30;
    private static final String DEFAULT_SERVICE_NAME         = "unknown-service";

    private final int    intervalSeconds;
    private final int    gracePeriodSeconds;
    private final String endpoint;
    private final String serviceName;
    private final String headers;

    private AgentConfig(Properties props) {
        this.intervalSeconds    = getInt(props, "coverage.interval",     "JACOCO_OTEL_INTERVAL",     DEFAULT_INTERVAL_SECONDS);
        this.gracePeriodSeconds = getInt(props, "coverage.grace.period", "JACOCO_OTEL_GRACE_PERIOD", DEFAULT_GRACE_PERIOD_SECONDS);
        this.endpoint           = get   (props, "otel.endpoint",         "JACOCO_OTEL_ENDPOINT",     null);
        this.serviceName        = get   (props, "otel.service.name",     "JACOCO_OTEL_SERVICE_NAME", DEFAULT_SERVICE_NAME);
        this.headers            = get   (props, "otel.headers",          "JACOCO_OTEL_HEADERS",      null);
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Parses config from agent args (standalone mode).
     *
     * <p>Agent argument formats:
     * <ul>
     *   <li>{@code config=/path/to/file.properties} — load from file</li>
     *   <li>{@code interval=60,endpoint=http://...,service=my-app} — inline key=value pairs</li>
     *   <li>(no args) — use env vars and built-in defaults</li>
     * </ul>
     */
    public static AgentConfig parse(String agentArgs) {
        Properties props = new Properties();

        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            String trimmed = agentArgs.trim();
            if (trimmed.startsWith("config=")) {
                loadFromFile(props, trimmed.substring("config=".length()));
            } else {
                parseInline(props, trimmed);
                // If an inline "config" key was also given, load that file too
                // (inline values still override file values)
                String filePath = props.getProperty("config");
                if (filePath != null) {
                    Properties fileProps = new Properties();
                    loadFromFile(fileProps, filePath);
                    for (String key : fileProps.stringPropertyNames()) {
                        if (!props.containsKey(key)) {
                            props.setProperty(key, fileProps.getProperty(key));
                        }
                    }
                }
            }
        }

        return new AgentConfig(props);
    }

    /**
     * Reads config from environment variables only (extension mode).
     *
     * <p>In extension mode the jar is loaded by the zero-code agent, not via
     * {@code -javaagent:}, so there are no agent args to parse. Only
     * {@code JACOCO_OTEL_INTERVAL}, {@code JACOCO_OTEL_GRACE_PERIOD}, etc. apply.
     * {@code JACOCO_OTEL_ENDPOINT} and {@code JACOCO_OTEL_SERVICE_NAME} are not used
     * in extension mode (the zero-code agent owns the exporter configuration).
     */
    public static AgentConfig forExtensionMode() {
        return new AgentConfig(new Properties());
    }

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    private static void loadFromFile(Properties target, String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            JacocoOtelAgent.warn("Config file not found: " + filePath);
            return;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            target.load(fis);
            JacocoOtelAgent.log("Config loaded from " + filePath);
        } catch (IOException e) {
            JacocoOtelAgent.warn("Failed to load config file: " + e.getMessage());
        }
    }

    private static void parseInline(Properties target, String args) {
        for (String pair : args.split(",")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                target.setProperty(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
            }
        }
    }

    private static String get(Properties props, String key, String envVar, String defaultValue) {
        String envVal = System.getenv(envVar);
        if (envVal != null && !envVal.isEmpty()) return envVal;
        String propVal = props.getProperty(key);
        if (propVal != null && !propVal.isEmpty()) return propVal;
        return defaultValue;
    }

    private static int getInt(Properties props, String key, String envVar, int defaultValue) {
        String val = get(props, key, envVar, null);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            JacocoOtelAgent.warn("Invalid integer for " + key + ": " + val +
                " — using default " + defaultValue);
            return defaultValue;
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int    getIntervalSeconds()    { return intervalSeconds; }
    public int    getGracePeriodSeconds() { return gracePeriodSeconds; }
    public String getEndpoint()           { return endpoint; }
    public String getServiceName()        { return serviceName; }
    public String getHeaders()            { return headers; }

    @Override
    public String toString() {
        return "AgentConfig{interval=" + intervalSeconds + "s, gracePeriod=" + gracePeriodSeconds +
               "s, endpoint=" + endpoint + ", service=" + serviceName + "}";
    }
}

package io.jacoco.otel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Agent configuration.
 *
 * <p>Precedence (highest to lowest):
 * <ol>
 *   <li>Environment variables (JACOCO_OTEL_*)</li>
 *   <li>Agent argument inline key=value pairs</li>
 *   <li>Properties file referenced by {@code config=<path>}</li>
 *   <li>Built-in defaults</li>
 * </ol>
 */
public class AgentConfig {

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------
    private static final int    DEFAULT_INTERVAL_SECONDS     = 60;
    private static final int    DEFAULT_GRACE_PERIOD_SECONDS = 30;
    private static final String DEFAULT_PROTOCOL             = "http/protobuf";
    private static final String DEFAULT_SERVICE_NAME         = "unknown-service";
    private static final String DEFAULT_MODE                 = "auto";

    /**
     * OTel resolution mode.
     * <ul>
     *   <li>{@code auto} — detect automatically: check OTel env vars, then inspect
     *       {@code GlobalOpenTelemetry}; use inherit if either is present, standalone otherwise.</li>
     *   <li>{@code inherit} — always use {@code GlobalOpenTelemetry.get()} (the app's own SDK).
     *       Useful when auto-detection is unreliable in your environment.</li>
     *   <li>{@code standalone} — always create the agent's own OTel SDK from {@code otel.*} config.
     *       Useful when you want the coverage metrics completely isolated from the app's OTel setup.</li>
     * </ul>
     */
    public enum Mode { AUTO, INHERIT, STANDALONE }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------
    private final int    intervalSeconds;
    private final int    gracePeriodSeconds;
    private final Mode   mode;
    private final String endpoint;
    private final String serviceName;
    /** http/protobuf | grpc */
    private final String protocol;
    /** Optional extra headers sent to the collector: "key=val,key=val" */
    private final String headers;

    private AgentConfig(Properties props) {
        this.intervalSeconds    = getInt (props, "coverage.interval",     "JACOCO_OTEL_INTERVAL",      DEFAULT_INTERVAL_SECONDS);
        this.gracePeriodSeconds = getInt (props, "coverage.grace.period", "JACOCO_OTEL_GRACE_PERIOD",  DEFAULT_GRACE_PERIOD_SECONDS);
        this.mode               = getMode(props, "otel.mode",             "JACOCO_OTEL_MODE",          DEFAULT_MODE);
        this.endpoint           = get    (props, "otel.endpoint",         "JACOCO_OTEL_ENDPOINT",      null);
        this.serviceName        = get    (props, "otel.service.name",     "JACOCO_OTEL_SERVICE_NAME",  DEFAULT_SERVICE_NAME);
        this.protocol           = get    (props, "otel.protocol",         "JACOCO_OTEL_PROTOCOL",      DEFAULT_PROTOCOL);
        this.headers            = get    (props, "otel.headers",          "JACOCO_OTEL_HEADERS",       null);
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    public static AgentConfig parse(String agentArgs) {
        Properties props = new Properties();

        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            String trimmed = agentArgs.trim();
            if (trimmed.startsWith("config=")) {
                // Single argument: path to a .properties file
                String filePath = trimmed.substring("config=".length());
                loadFromFile(props, filePath);
            } else {
                // Inline comma-separated key=value pairs (commas within values not supported)
                parseInline(props, trimmed);
                // If an inline "config" key was also specified, load that file too
                // (inline values still override file values — env vars override both)
                String filePath = props.getProperty("config");
                if (filePath != null) {
                    Properties fileProps = new Properties();
                    loadFromFile(fileProps, filePath);
                    // merge: existing inline values win
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
            JacocoOtelAgent.warn("Invalid integer for " + key + ": " + val + " — using default " + defaultValue);
            return defaultValue;
        }
    }

    private static Mode getMode(Properties props, String key, String envVar, String defaultValue) {
        String val = get(props, key, envVar, defaultValue);
        if (val == null) return Mode.AUTO;
        switch (val.trim().toLowerCase()) {
            case "inherit":    return Mode.INHERIT;
            case "standalone": return Mode.STANDALONE;
            case "auto":       return Mode.AUTO;
            default:
                JacocoOtelAgent.warn("Unknown otel.mode value: '" + val + "' — using 'auto'");
                return Mode.AUTO;
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int    getIntervalSeconds()    { return intervalSeconds; }
    public int    getGracePeriodSeconds() { return gracePeriodSeconds; }
    public Mode   getMode()              { return mode; }
    public String getEndpoint()           { return endpoint; }
    public String getServiceName()        { return serviceName; }
    public String getProtocol()           { return protocol; }
    public String getHeaders()            { return headers; }

    @Override
    public String toString() {
        return "AgentConfig{interval=" + intervalSeconds + "s, gracePeriod=" + gracePeriodSeconds +
               "s, mode=" + mode.name().toLowerCase() + ", endpoint=" + endpoint +
               ", service=" + serviceName + "}";
    }
}

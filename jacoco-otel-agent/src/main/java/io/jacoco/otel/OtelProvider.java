package io.jacoco.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

/**
 * Resolves the {@link OpenTelemetry} instance to use for metric recording.
 *
 * <h3>Mode</h3>
 * <ul>
 *   <li><b>auto</b> (default) — detect automatically:
 *     <ol>
 *       <li>Are standard OTel env vars set ({@code OTEL_EXPORTER_OTLP_ENDPOINT} etc.)?</li>
 *       <li>Has {@link GlobalOpenTelemetry#get()} been populated with a non-noop instance?</li>
 *       <li>If either: <b>inherit</b>; otherwise: <b>standalone</b>.</li>
 *     </ol>
 *   </li>
 *   <li><b>inherit</b> — always use {@link GlobalOpenTelemetry#get()} (the app's own SDK).</li>
 *   <li><b>standalone</b> — always create the agent's own {@link OpenTelemetrySdk} from config.</li>
 * </ul>
 *
 * <p>Resolution is <b>lazy</b> (first call after the grace period) and <b>cached</b> — the
 * decision is made once and never revisited.
 */
public class OtelProvider {

    private static final String METER_NAME = "io.jacoco.otel";

    private final AgentConfig config;
    /** Null until first call to {@link #getMeter()}. */
    private volatile OpenTelemetry resolved;

    public OtelProvider(AgentConfig config) {
        this.config = config;
    }

    /** Returns a {@link Meter} backed by the resolved {@link OpenTelemetry} instance. */
    public Meter getMeter() {
        return getOrResolve().getMeter(METER_NAME);
    }

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    private OpenTelemetry getOrResolve() {
        if (resolved != null) return resolved;
        synchronized (this) {
            if (resolved != null) return resolved;
            resolved = resolve();
        }
        return resolved;
    }

    private OpenTelemetry resolve() {
        AgentConfig.Mode mode = config.getMode();

        if (mode == AgentConfig.Mode.INHERIT) {
            JacocoOtelAgent.log("OTel mode: inherit (forced) — " +
                "using existing OTel configuration; endpoint, service name, and resource attributes " +
                "are inherited from the app's setup. JACOCO_OTEL_ENDPOINT / otel.endpoint are not used.");
            warnIfStandalonePropertiesSet();
            OpenTelemetry otel = GlobalOpenTelemetry.get();
            JacocoOtelAgent.log("OTel metric export interval: " + detectExportInterval(otel));
            return otel;
        }

        if (mode == AgentConfig.Mode.STANDALONE) {
            JacocoOtelAgent.log("OTel mode: standalone (forced) — creating agent's own SDK.");
            return createStandaloneSdk();
        }

        // mode == AUTO: detect from env vars and GlobalOpenTelemetry state
        if (autoDetectInherit()) {
            JacocoOtelAgent.log("OTel mode: inherit — " +
                "existing OTel configuration detected; endpoint, service name, and resource attributes " +
                "are inherited from the app's setup. JACOCO_OTEL_ENDPOINT / otel.endpoint are not used.");
            warnIfStandalonePropertiesSet();
            OpenTelemetry otel = GlobalOpenTelemetry.get();
            JacocoOtelAgent.log("OTel metric export interval: " + detectExportInterval(otel));
            return otel;
        } else {
            JacocoOtelAgent.log("OTel mode: standalone — no existing OTel configuration detected. " +
                "Creating agent's own SDK. JACOCO_OTEL_ENDPOINT and JACOCO_OTEL_SERVICE_NAME must be set.");
            return createStandaloneSdk();
        }
    }

    /**
     * If the user has set standalone-only properties (endpoint, service name) but we ended up
     * in inherit mode, log a notice so they know those settings are not being used.
     */
    private void warnIfStandalonePropertiesSet() {
        if (config.getEndpoint() != null || isEnvSet("JACOCO_OTEL_ENDPOINT")) {
            JacocoOtelAgent.log("Note: JACOCO_OTEL_ENDPOINT is set but not used — " +
                "the app's existing OTel configuration handles the export destination.");
        }
    }

    /**
     * Auto-detection: returns true if an existing OTel configuration is found.
     *
     * <p>Two reliable signals are checked — no fragile class-scanning:
     * <ol>
     *   <li>Standard OTel environment variables set by the user or a platform.</li>
     *   <li>{@link GlobalOpenTelemetry#get()} already holds a non-noop instance,
     *       meaning the app's SDK or zero-code agent has already initialized it.</li>
     * </ol>
     */
    private boolean autoDetectInherit() {
        // 1. User has set standard OTel env vars (zero-code agent, manual SDK, or platform config)
        if (isEnvSet("OTEL_EXPORTER_OTLP_ENDPOINT") ||
            isEnvSet("OTEL_TRACES_EXPORTER") ||
            isEnvSet("OTEL_METRICS_EXPORTER") ||
            isEnvSet("OTEL_SERVICE_NAME")) {
            JacocoOtelAgent.log("OTel environment variables detected");
            return true;
        }

        // 2. Something has already registered a non-noop global instance
        OpenTelemetry global = GlobalOpenTelemetry.get();
        String className = global.getClass().getName();
        boolean isNoop = className.contains("Noop") || className.contains("DefaultOpenTelemetry");
        if (!isNoop) {
            JacocoOtelAgent.log("Non-noop GlobalOpenTelemetry detected: " + className);
            return true;
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Standalone SDK
    // -------------------------------------------------------------------------

    private OpenTelemetry createStandaloneSdk() {
        String endpoint = config.getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            JacocoOtelAgent.warn("No OTLP endpoint configured for standalone mode. " +
                "Set JACOCO_OTEL_ENDPOINT or otel.endpoint in config. Metrics will not be exported.");
            return OpenTelemetry.noop();
        }

        Resource resource = Resource.getDefault()
            .merge(parseOtelResourceAttributes())
            .merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), config.getServiceName()
            )));

        // Normalize endpoint: OtlpHttpMetricExporter.setEndpoint() takes the full signal URL
        // in OTel SDK 1.38+, so append /v1/metrics if the user supplied only the base URL.
        String signalEndpoint = endpoint;
        if (!signalEndpoint.contains("/v1/metrics")) {
            signalEndpoint = signalEndpoint.replaceAll("/+$", "") + "/v1/metrics";
        }

        OtlpHttpMetricExporter exporter = buildExporter(signalEndpoint, config.getHeaders());

        PeriodicMetricReader reader = PeriodicMetricReader.builder(exporter)
            .setInterval(Duration.ofSeconds(config.getIntervalSeconds()))
            .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(reader)
            .build();

        final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .build();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                sdk.close();
            }
        }, "jacoco-otel-shutdown"));

        JacocoOtelAgent.log("Standalone OTel SDK created. Endpoint: " + signalEndpoint +
            ", service: " + config.getServiceName());
        return sdk;
    }

    /**
     * Parses {@code OTEL_RESOURCE_ATTRIBUTES} (e.g. {@code key1=val1,key2=val2}) into a
     * {@link Resource}. The core OTel SDK does not include the autoconfigure extension, so
     * {@link Resource#getDefault()} does not read this env var on its own.
     */
    private static Resource parseOtelResourceAttributes() {
        String raw = System.getenv("OTEL_RESOURCE_ATTRIBUTES");
        if (raw == null || raw.trim().isEmpty()) {
            return Resource.empty();
        }
        AttributesBuilder builder = Attributes.builder();
        for (String pair : raw.split(",")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                builder.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
            }
        }
        return Resource.create(builder.build());
    }

    private static OtlpHttpMetricExporter buildExporter(String endpoint, String headers) {
        OtlpHttpMetricExporterBuilder builder = OtlpHttpMetricExporter.builder()
            .setEndpoint(endpoint);
        if (headers != null && !headers.trim().isEmpty()) {
            for (String pair : headers.split(",")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    builder.addHeader(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
                }
            }
        }
        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Discovers the OTel metric export interval from the inherited SDK.
     *
     * <p>Tries three sources in order:
     * <ol>
     *   <li>{@code OTEL_METRIC_EXPORT_INTERVAL} environment variable (set by the operator)</li>
     *   <li>{@code otel.metric.export.interval} system property</li>
     *   <li>Reflection into the SDK's {@code PeriodicMetricReader} — works regardless of whether
     *       the interval was configured via env var, system property, or programmatic SDK setup</li>
     * </ol>
     * Falls back to reporting the OTel default (60 000 ms) if all three fail.
     */
    private static String detectExportInterval(OpenTelemetry otel) {
        // 1. Env var — fastest check, set by the zero-code agent or the platform
        String envVal = System.getenv("OTEL_METRIC_EXPORT_INTERVAL");
        if (envVal != null && !envVal.isEmpty()) {
            return envVal + "ms (from OTEL_METRIC_EXPORT_INTERVAL)";
        }

        // 2. System property
        String propVal = System.getProperty("otel.metric.export.interval");
        if (propVal != null && !propVal.isEmpty()) {
            return propVal + "ms (from otel.metric.export.interval)";
        }

        // 3. Reflect into the live SDK — handles programmatic configuration that has no
        //    corresponding env var or system property.
        try {
            // OpenTelemetrySdk.getMeterProvider() → SdkMeterProvider
            Method getMeterProvider = otel.getClass().getMethod("getMeterProvider");
            Object meterProvider = getMeterProvider.invoke(otel);

            // SdkMeterProvider keeps its readers in a private List<RegisteredReader> field.
            // Walk the class hierarchy in case a subclass is involved.
            Field readersField = findField(meterProvider.getClass(), "registeredReaders");
            if (readersField == null) {
                return "unknown (SDK internal field not found; default: 60000ms)";
            }
            readersField.setAccessible(true);
            List<?> registeredReaders = (List<?>) readersField.get(meterProvider);

            for (Object registeredReader : registeredReaders) {
                // RegisteredReader.getReader() or a public/package-private accessor
                Object reader = getReaderFromRegisteredReader(registeredReader);
                if (reader == null) continue;

                if (reader.getClass().getName().contains("PeriodicMetricReader")) {
                    Field intervalField = findField(reader.getClass(), "intervalNanos");
                    if (intervalField != null) {
                        intervalField.setAccessible(true);
                        long nanos = (Long) intervalField.get(reader);
                        return (nanos / 1_000_000L) + "ms (from SDK reflection)";
                    }
                }
            }
        } catch (Exception ignored) {
            // Reflection failed — not fatal, fall through to default
        }

        return "unknown (default: 60000ms)";
    }

    /** Walks the class hierarchy to find a declared field by name. */
    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** Extracts the raw {@code MetricReader} from a {@code RegisteredReader} wrapper via reflection. */
    private static Object getReaderFromRegisteredReader(Object registeredReader) {
        // Try a public/package-private getReader() method first
        try {
            Method m = registeredReader.getClass().getDeclaredMethod("getReader");
            m.setAccessible(true);
            return m.invoke(registeredReader);
        } catch (Exception ignored) {
        }
        // Fall back to a field named "reader"
        try {
            Field f = findField(registeredReader.getClass(), "reader");
            if (f != null) {
                f.setAccessible(true);
                return f.get(registeredReader);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isEnvSet(String name) {
        String val = System.getenv(name);
        return val != null && !val.isEmpty();
    }
}

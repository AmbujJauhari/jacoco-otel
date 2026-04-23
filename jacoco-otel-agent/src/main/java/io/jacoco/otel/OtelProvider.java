package io.jacoco.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;

import java.time.Duration;

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
            return GlobalOpenTelemetry.get();
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
            return GlobalOpenTelemetry.get();
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

        Resource resource = Resource.getDefault().merge(
            Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), config.getServiceName()
            ))
        );

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

    private static boolean isEnvSet(String name) {
        String val = System.getenv(name);
        return val != null && !val.isEmpty();
    }
}

package io.jacoco.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;

import java.time.Duration;

/**
 * Factory that builds the standalone {@link OpenTelemetry} SDK used when the agent is
 * deployed without the OTel zero-code agent (standalone mode).
 *
 * <p>In extension mode the SDK is provided by the zero-code agent via
 * {@link io.opentelemetry.javaagent.extension.AgentListener#afterAgent} and this class
 * is not used.
 */
public final class OtelProvider {

    private OtelProvider() {}

    /**
     * Creates a fully configured {@link OpenTelemetrySdk} that exports metrics to the OTLP
     * HTTP endpoint specified in {@code config}.
     *
     * <p>Returns {@link OpenTelemetry#noop()} (with a warning) when no endpoint is configured.
     */
    public static OpenTelemetry createStandalone(AgentConfig config) {
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

        // OtlpHttpMetricExporter.setEndpoint() takes the full signal URL.
        // Append /v1/metrics if the user supplied only the base URL.
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
}

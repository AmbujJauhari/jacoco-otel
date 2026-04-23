package io.jacoco.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

/**
 * OTel zero-code agent extension entry point — handles <b>extension mode</b>.
 *
 * <p>Extension mode: this jar is loaded by the OTel zero-code agent via
 * {@code -Dotel.javaagent.extensions=/path/to/jacoco-otel-agent.jar}.
 * The zero-code agent calls {@link #afterAgent} once its SDK is fully initialized,
 * providing the live {@link OpenTelemetry} instance directly — no polling, no race condition.
 *
 * <p>Discovered by the zero-code agent via the standard SPI file:
 * {@code META-INF/services/io.opentelemetry.javaagent.extension.AgentListener}.
 *
 * <p>The typical deployment command:
 * <pre>
 *   java \
 *     -javaagent:otel-agent.jar \
 *     -Dotel.javaagent.extensions=/path/to/jacoco-otel-agent.jar \
 *     -javaagent:jacoco-agent.jar=output=none,includes=com.example.* \
 *     -jar app.jar
 * </pre>
 *
 * <p>No {@code JACOCO_OTEL_ENDPOINT} or {@code JACOCO_OTEL_SERVICE_NAME} is needed —
 * those are owned by the zero-code agent's configuration. Only
 * {@code JACOCO_OTEL_INTERVAL} and {@code JACOCO_OTEL_GRACE_PERIOD} are relevant.
 */
public class JacocoCoverageExtension implements AgentListener {

    @Override
    public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
        if (!JacocoOtelAgent.started.compareAndSet(false, true)) {
            JacocoOtelAgent.log("Already started via standalone mode — skipping extension init");
            return;
        }

        try {
            JacocoOtelAgent.log("Starting in extension mode (OTel SDK ready)");
            AgentConfig config = AgentConfig.forExtensionMode();
            OpenTelemetry otel = autoConfiguredSdk.getOpenTelemetrySdk();
            new CoverageMetricsReporter(config, otel).start();
        } catch (Exception e) {
            System.err.println("[jacoco-otel-agent] Failed to start in extension mode: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }
}

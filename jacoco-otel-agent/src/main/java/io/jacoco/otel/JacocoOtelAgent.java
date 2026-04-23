package io.jacoco.otel;

import io.opentelemetry.api.OpenTelemetry;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java agent entry point — handles <b>standalone mode</b>.
 *
 * <p>Standalone mode: the agent is loaded via {@code -javaagent:jacoco-otel-agent.jar}
 * without the OTel zero-code agent present. The agent creates its own
 * {@link OpenTelemetry} SDK and exports coverage metrics to the configured OTLP endpoint.
 *
 * <p>For applications that already run the OTel zero-code agent, load this jar as an
 * <b>extension</b> instead:
 * <pre>
 *   -javaagent:otel-agent.jar
 *   -Dotel.javaagent.extensions=/path/to/jacoco-otel-agent.jar
 * </pre>
 * In extension mode {@link JacocoCoverageExtension#afterAgent} is the entry point and
 * this class is not involved.
 *
 * <h3>Agent argument formats</h3>
 * <ul>
 *   <li>{@code config=/path/to/jacoco-otel-agent.properties} — load config from file</li>
 *   <li>{@code interval=60,endpoint=http://localhost:4318,service=my-app} — inline key=value</li>
 *   <li>(no args) — configure entirely via {@code JACOCO_OTEL_*} environment variables</li>
 * </ul>
 */
public class JacocoOtelAgent {

    /**
     * Guards against double-start when the jar is accidentally loaded both as a
     * {@code -javaagent:} and as an OTel extension. Whichever entry point fires first wins.
     */
    static final AtomicBoolean started = new AtomicBoolean(false);

    /** Called by the JVM when listed as {@code -javaagent:} at JVM startup. */
    public static void premain(String agentArgs, Instrumentation inst) {
        if (!started.compareAndSet(false, true)) {
            log("Already started via extension mode — skipping standalone init");
            return;
        }
        startStandalone(agentArgs);
    }

    /** Called by the JVM when attached to a running JVM via the Attach API. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        if (!started.compareAndSet(false, true)) {
            log("Already started — skipping duplicate init");
            return;
        }
        startStandalone(agentArgs);
    }

    private static void startStandalone(String agentArgs) {
        try {
            log("Starting in standalone mode (args: " + (agentArgs == null ? "<none>" : agentArgs) + ")");
            AgentConfig config = AgentConfig.parse(agentArgs);
            OpenTelemetry otel = OtelProvider.createStandalone(config);
            new CoverageMetricsReporter(config, otel).start();
        } catch (Exception e) {
            System.err.println("[jacoco-otel-agent] Failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    static void log(String message) {
        System.out.println("[jacoco-otel-agent] " + message);
    }

    static void warn(String message) {
        System.err.println("[jacoco-otel-agent] WARN: " + message);
    }
}

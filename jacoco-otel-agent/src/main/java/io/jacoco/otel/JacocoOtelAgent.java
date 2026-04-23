package io.jacoco.otel;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point.
 *
 * <p>Attach statically via {@code -javaagent:jacoco-otel-agent.jar[=<args>]} or
 * dynamically via the Attach API (agentmain).
 *
 * <p>Agent argument formats:
 * <ul>
 *   <li>{@code config=/path/to/jacoco-otel-agent.properties} — load config from file</li>
 *   <li>{@code interval=60,endpoint=http://localhost:4318,service=my-app} — inline key=value</li>
 *   <li>(no args) — all defaults; set env vars JACOCO_OTEL_* to configure</li>
 * </ul>
 */
public class JacocoOtelAgent {

    private static volatile Instrumentation instrumentation;

    /** Called by the JVM when the agent is listed as -javaagent at JVM startup. */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        start(agentArgs, inst);
    }

    /** Called by the JVM when the agent is attached to a running JVM via Attach API. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        start(agentArgs, inst);
    }

    private static void start(String agentArgs, Instrumentation inst) {
        try {
            log("Starting (args: " + (agentArgs == null ? "<none>" : agentArgs) + ")");
            AgentConfig config = AgentConfig.parse(agentArgs);
            CoverageMetricsReporter reporter = new CoverageMetricsReporter(config, inst);
            reporter.start();
        } catch (Exception e) {
            System.err.println("[jacoco-otel-agent] Failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    static void log(String message) {
        System.out.println("[jacoco-otel-agent] " + message);
    }

    static void warn(String message) {
        System.err.println("[jacoco-otel-agent] WARN: " + message);
    }
}

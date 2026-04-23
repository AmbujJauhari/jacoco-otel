package io.jacoco.otel;

import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;

import java.io.ByteArrayInputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reads JaCoCo execution data by locating JaCoCo's internal Agent singleton via
 * {@link Instrumentation#getAllLoadedClasses()}.
 *
 * <p>This approach avoids any dependency on class names (which are subject to the
 * Maven Shade plugin's relocation) or on the JMX MBean server (which Spring Boot
 * wraps in a delegate that breaks {@code isRegistered} and {@code invoke}).
 *
 * <p>Strategy: scan loaded classes for one that:
 * <ol>
 *   <li>Is in a package containing "jacoco" and "internal"</li>
 *   <li>Has a declared {@code getExecutionData(boolean): byte[]} method</li>
 *   <li>Has a non-null static field of its own type (the singleton instance)</li>
 * </ol>
 *
 * <p>JaCoCo must be attached as a {@code -javaagent}:
 * <pre>
 *   -javaagent:jacoco-agent.jar=output=none
 * </pre>
 */
public class JacocoCoverageReader {

    private static final String GET_EXEC_DATA = "getExecutionData";

    /** Cached agent instance once found. */
    private volatile Object cachedAgent;

    /**
     * Reads the current JaCoCo execution data.
     *
     * @return parsed {@link ExecutionDataStore}, or {@code null} if JaCoCo is not
     *         available or no data has been collected yet.
     */
    public ExecutionDataStore read() {
        try {
            byte[] data = readFromAgent();

            if (data == null) {
                JacocoOtelAgent.warn("JaCoCo agent not found. " +
                    "Ensure the JaCoCo agent is attached: -javaagent:jacoco-agent.jar=output=none");
                return null;
            }

            if (data.length == 0) {
                JacocoOtelAgent.log("JaCoCo returned empty execution data (no classes loaded yet?)");
                return null;
            }

            ExecutionDataStore dataStore    = new ExecutionDataStore();
            SessionInfoStore   sessionStore = new SessionInfoStore();

            ExecutionDataReader reader = new ExecutionDataReader(new ByteArrayInputStream(data));
            reader.setExecutionDataVisitor(dataStore);
            reader.setSessionInfoVisitor(sessionStore);
            reader.read();

            JacocoOtelAgent.log("Read execution data: " + dataStore.getContents().size()
                + " class(es) tracked by JaCoCo");
            return dataStore;

        } catch (Exception e) {
            JacocoOtelAgent.warn("Error reading JaCoCo data: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Agent access
    // -------------------------------------------------------------------------

    private byte[] readFromAgent() throws Exception {
        if (cachedAgent == null) {
            cachedAgent = findAgentInstance();
        }
        if (cachedAgent == null) {
            return null;
        }

        // Call getExecutionData(false) — matched by signature, not by class name,
        // so shade-plugin relocation of string constants cannot affect this.
        for (Method m : cachedAgent.getClass().getMethods()) {
            if (GET_EXEC_DATA.equals(m.getName())
                    && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == boolean.class
                    && m.getReturnType() == byte[].class) {
                return (byte[]) m.invoke(cachedAgent, Boolean.FALSE);
            }
        }

        JacocoOtelAgent.warn("getExecutionData not found on " + cachedAgent.getClass().getName());
        cachedAgent = null;
        return null;
    }

    /**
     * Locates JaCoCo's internal Agent singleton without relying on any specific class
     * name string (which would be rewritten by the Maven Shade plugin).
     *
     * <p>Identification criteria (applied in combination):
     * <ul>
     *   <li>Class is in a package whose name contains both "jacoco" and "internal"
     *       (matches {@code org.jacoco.agent.rt.internal_XXXX.Agent}).</li>
     *   <li>Class declares a {@code getExecutionData(boolean): byte[]} method.</li>
     *   <li>Class has a static field of its own type holding the live singleton.</li>
     * </ul>
     */
    private static Object findAgentInstance() {
        Instrumentation inst = JacocoOtelAgent.getInstrumentation();
        if (inst == null) return null;

        for (Class<?> cls : inst.getAllLoadedClasses()) {
            if (cls.isInterface() || cls.isArray() || cls.isPrimitive()) continue;

            // Must be in a JaCoCo internal package
            String pkg = cls.getName();
            if (!pkg.contains("jacoco") || !pkg.contains("internal")) continue;

            // Must declare getExecutionData(boolean): byte[]
            if (!hasGetExecutionData(cls)) continue;

            // Find the static singleton field of the same type
            Object instance = getStaticSingletonField(cls);
            if (instance != null) {
                JacocoOtelAgent.log("JaCoCo agent found: " + cls.getName());
                return instance;
            }
        }
        return null;
    }

    private static boolean hasGetExecutionData(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (GET_EXEC_DATA.equals(m.getName())
                    && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == boolean.class
                    && m.getReturnType() == byte[].class) {
                return true;
            }
        }
        return false;
    }

    private static Object getStaticSingletonField(Class<?> cls) {
        for (Field f : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            // Field type must be assignable from the class (i.e. same type or supertype)
            if (!f.getType().isAssignableFrom(cls)) continue;
            try {
                f.setAccessible(true);
                Object value = f.get(null);
                if (value != null) return value;
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}

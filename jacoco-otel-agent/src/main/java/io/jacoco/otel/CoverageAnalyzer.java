package io.jacoco.otel;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Computes coverage ratios by analyzing class bytecode against JaCoCo execution data.
 *
 * <h3>Class discovery strategy</h3>
 * <p>Only classes that JaCoCo actually instrumented (i.e., those present in the
 * {@link ExecutionDataStore}) are analyzed. This avoids including library classes
 * whose coverage was never tracked and accurately reflects what JaCoCo measured.
 *
 * <p>Class bytecode is located by scanning {@code java.class.path}:
 * <ul>
 *   <li>Plain directories — searched recursively for {@code .class} files.</li>
 *   <li>JAR files — entries are scanned directly.</li>
 *   <li>Spring Boot fat JARs — detected by the presence of a {@code BOOT-INF/classes/}
 *       entry; only that subtree is scanned (library jars in {@code BOOT-INF/lib/}
 *       are skipped).</li>
 * </ul>
 */
public class CoverageAnalyzer {

    /** Internal form, e.g. "io/jacoco/otel/CoverageAnalyzer" */
    private static final String OWN_PREFIX = "io/jacoco/otel/";

    @SuppressWarnings("unused")
    private final Instrumentation instrumentation; // reserved for future class-byte capture

    public CoverageAnalyzer(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Analyzes the supplied execution data and returns overall coverage ratios.
     *
     * @param executionData data produced by {@link JacocoCoverageReader}
     * @return coverage ratios, never {@code null}
     */
    public CoverageSummary analyze(ExecutionDataStore executionData) {
        // Build the set of class names JaCoCo has tracked (internal form, e.g. "com/example/Foo")
        Set<String> trackedClasses = new HashSet<String>();
        for (ExecutionData ed : executionData.getContents()) {
            trackedClasses.add(ed.getName());
        }

        if (trackedClasses.isEmpty()) {
            JacocoOtelAgent.log("No classes in execution data yet — returning empty coverage");
            return CoverageSummary.empty();
        }

        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(executionData, coverageBuilder);

        int analyzed = scanClasspath(analyzer, trackedClasses);
        JacocoOtelAgent.log("Analyzed " + analyzed + " class file(s) out of " +
            trackedClasses.size() + " tracked by JaCoCo");

        IBundleCoverage bundle = coverageBuilder.getBundle("Application");
        return summarize(bundle);
    }

    // -------------------------------------------------------------------------
    // Classpath scanning
    // -------------------------------------------------------------------------

    private int scanClasspath(Analyzer analyzer, Set<String> trackedClasses) {
        String classpath = System.getProperty("java.class.path", "");
        int analyzed = 0;

        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.trim().isEmpty()) continue;
            File file = new File(entry);
            if (!file.exists()) continue;

            try {
                if (file.isDirectory()) {
                    // Pass file as both the classpath root and the starting directory so
                    // we can compute internal class names (e.g. "com/example/Foo") relative
                    // to the root and filter against trackedClasses.
                    analyzed += analyzeDirectory(analyzer, file, file, trackedClasses);
                } else if (isJar(file)) {
                    analyzed += analyzeJar(analyzer, file, trackedClasses);
                }
            } catch (Exception e) {
                JacocoOtelAgent.warn("Skipping classpath entry " + entry + ": " + e.getMessage());
            }
        }

        return analyzed;
    }

    /**
     * Recursively finds .class files in {@code current} and feeds matching ones to the Analyzer.
     *
     * @param root the classpath entry root (used to derive the internal class name)
     * @param current the directory being scanned in this recursive call
     */
    private int analyzeDirectory(Analyzer analyzer, File root, File current,
                                 Set<String> trackedClasses) {
        int count = 0;
        File[] files = current.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            if (file.isDirectory()) {
                count += analyzeDirectory(analyzer, root, file, trackedClasses);
            } else if (file.getName().endsWith(".class")) {
                // Derive the internal class name (e.g. "com/example/Foo") by computing
                // the path relative to the classpath root and stripping the .class suffix.
                String relativePath = root.toURI().relativize(file.toURI()).getPath();
                String internalName  = relativePath.replace(".class", "");
                if (!trackedClasses.contains(internalName)) continue;

                try (InputStream is = new FileInputStream(file)) {
                    analyzer.analyzeClass(is, file.getPath());
                    count++;
                } catch (Exception e) {
                    // skip malformed / synthetic classes
                }
            }
        }
        return count;
    }

    /** Scans a JAR (or Spring Boot fat JAR) and feeds matching class entries to the Analyzer. */
    private int analyzeJar(Analyzer analyzer, File jarFile, Set<String> trackedClasses)
            throws IOException {
        int count = 0;

        try (JarFile jar = new JarFile(jarFile)) {
            boolean isSpringBootFatJar = isSpringBootFatJar(jar);

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.endsWith(".class")) continue;

                // For Spring Boot fat JARs, only process the embedded app classes
                if (isSpringBootFatJar) {
                    if (!name.startsWith("BOOT-INF/classes/")) continue;
                    // strip the prefix to get the internal class name
                    String internalName = name.substring("BOOT-INF/classes/".length())
                        .replace(".class", "");
                    if (!trackedClasses.contains(internalName)) continue;
                } else {
                    String internalName = name.replace(".class", "");
                    if (!trackedClasses.contains(internalName)) continue;
                }

                // Skip our own agent classes (shouldn't be instrumented by JaCoCo, but just in case)
                if (name.startsWith(OWN_PREFIX) || name.contains("/shaded/")) continue;

                try (InputStream is = jar.getInputStream(entry)) {
                    analyzer.analyzeClass(is, name);
                    count++;
                } catch (Exception e) {
                    // skip malformed class
                }
            }
        }

        return count;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isSpringBootFatJar(JarFile jar) {
        return jar.getEntry("BOOT-INF/classes/") != null;
    }

    private static boolean isJar(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear");
    }

    private static CoverageSummary summarize(IBundleCoverage bundle) {
        return new CoverageSummary(
            ratio(bundle.getInstructionCounter()),
            ratio(bundle.getBranchCounter()),
            ratio(bundle.getLineCounter()),
            ratio(bundle.getMethodCounter()),
            ratio(bundle.getClassCounter())
        );
    }

    private static double ratio(ICounter counter) {
        int total = counter.getTotalCount();
        return total == 0 ? 0.0 : (double) counter.getCoveredCount() / total;
    }
}

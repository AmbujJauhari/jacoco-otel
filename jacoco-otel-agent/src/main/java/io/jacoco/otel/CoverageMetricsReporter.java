package io.jacoco.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import org.jacoco.core.data.ExecutionDataStore;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Orchestrates the periodic coverage collection and OTel metric export.
 *
 * <h3>Startup flow</h3>
 * <ol>
 *   <li>Wait {@code max(gracePeriod, interval)} seconds before the first collection.
 *       This gives other Java agents (e.g., OTel zero-code agent, JaCoCo) time to
 *       initialize before we attempt to read the MBean or inspect GlobalOpenTelemetry.</li>
 *   <li>On first tick: read JaCoCo → analyze → update {@link CoverageCache} →
 *       resolve OTel (lazy, cached) → register gauge callbacks once.</li>
 *   <li>On subsequent ticks: read JaCoCo → analyze → update cache.
 *       The gauge callbacks read from the cache whenever the OTel SDK collector fires.</li>
 * </ol>
 *
 * <h3>Metrics produced</h3>
 * <pre>
 *   jacoco.coverage.instructions.ratio  {scope="total"}  [0.0 – 1.0]
 *   jacoco.coverage.branches.ratio      {scope="total"}  [0.0 – 1.0]
 *   jacoco.coverage.lines.ratio         {scope="total"}  [0.0 – 1.0]
 *   jacoco.coverage.methods.ratio       {scope="total"}  [0.0 – 1.0]
 *   jacoco.coverage.classes.ratio       {scope="total"}  [0.0 – 1.0]
 * </pre>
 */
public class CoverageMetricsReporter {

    private static final AttributeKey<String> SCOPE_KEY   = AttributeKey.stringKey("scope");
    private static final Attributes           TOTAL_ATTRS = Attributes.of(SCOPE_KEY, "total");

    private final AgentConfig             config;
    private final OtelProvider            otelProvider;
    private final JacocoCoverageReader    coverageReader;
    private final CoverageAnalyzer        coverageAnalyzer;
    private final CoverageCache           coverageCache;
    private final ScheduledExecutorService scheduler;

    /** Holds strong references to registered gauges so they are not garbage-collected. */
    private final List<AutoCloseable> gaugeHandles = new ArrayList<AutoCloseable>();
    private volatile boolean gaugesRegistered = false;

    public CoverageMetricsReporter(AgentConfig config, Instrumentation instrumentation) {
        this.config           = config;
        this.otelProvider     = new OtelProvider(config);
        this.coverageReader   = new JacocoCoverageReader();
        this.coverageAnalyzer = new CoverageAnalyzer(instrumentation);
        this.coverageCache    = new CoverageCache();
        this.scheduler        = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jacoco-otel-reporter");
                t.setDaemon(true); // must not prevent JVM shutdown
                return t;
            }
        });
    }

    /** Schedules the periodic coverage collection. */
    public void start() {
        JacocoOtelAgent.log("Config: " + config);
        long delaySeconds = Math.max(config.getGracePeriodSeconds(), config.getIntervalSeconds());
        JacocoOtelAgent.log("First collection in " + delaySeconds + "s, then every " +
            config.getIntervalSeconds() + "s");

        scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                tick();
            }
        }, delaySeconds, config.getIntervalSeconds(), TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    private void tick() {
        try {
            // 1. Read JaCoCo execution data via JMX MBean
            ExecutionDataStore executionData = coverageReader.read();
            if (executionData == null) return;

            // 2. Analyze against class bytecode and update the cache
            CoverageSummary summary = coverageAnalyzer.analyze(executionData);
            coverageCache.update(summary);
            JacocoOtelAgent.log(summary.toString());

            // 3. Register gauges once — OTel is resolved lazily on this first call
            if (!gaugesRegistered) {
                registerGauges();
                gaugesRegistered = true;
            }
        } catch (Exception e) {
            JacocoOtelAgent.warn("Error during coverage tick: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // OTel gauge registration
    // -------------------------------------------------------------------------

    private void registerGauges() {
        Meter meter = otelProvider.getMeter();

        // buildWithCallback takes Consumer<ObservableDoubleMeasurement> (java.util.function)
        // and returns ObservableDoubleGauge which implements AutoCloseable.

        gaugeHandles.add(
            meter.gaugeBuilder("jacoco.coverage.instructions.ratio")
                .setDescription("JaCoCo instruction coverage ratio (covered / total instructions)")
                .setUnit("1")
                .buildWithCallback(new Consumer<ObservableDoubleMeasurement>() {
                    public void accept(ObservableDoubleMeasurement m) {
                        CoverageSummary s = coverageCache.get();
                        if (s != null) m.record(s.instructions, TOTAL_ATTRS);
                    }
                })
        );

        gaugeHandles.add(
            meter.gaugeBuilder("jacoco.coverage.branches.ratio")
                .setDescription("JaCoCo branch coverage ratio (covered / total branches)")
                .setUnit("1")
                .buildWithCallback(new Consumer<ObservableDoubleMeasurement>() {
                    public void accept(ObservableDoubleMeasurement m) {
                        CoverageSummary s = coverageCache.get();
                        if (s != null) m.record(s.branches, TOTAL_ATTRS);
                    }
                })
        );

        gaugeHandles.add(
            meter.gaugeBuilder("jacoco.coverage.lines.ratio")
                .setDescription("JaCoCo line coverage ratio (covered / total lines)")
                .setUnit("1")
                .buildWithCallback(new Consumer<ObservableDoubleMeasurement>() {
                    public void accept(ObservableDoubleMeasurement m) {
                        CoverageSummary s = coverageCache.get();
                        if (s != null) m.record(s.lines, TOTAL_ATTRS);
                    }
                })
        );

        gaugeHandles.add(
            meter.gaugeBuilder("jacoco.coverage.methods.ratio")
                .setDescription("JaCoCo method coverage ratio (covered / total methods)")
                .setUnit("1")
                .buildWithCallback(new Consumer<ObservableDoubleMeasurement>() {
                    public void accept(ObservableDoubleMeasurement m) {
                        CoverageSummary s = coverageCache.get();
                        if (s != null) m.record(s.methods, TOTAL_ATTRS);
                    }
                })
        );

        gaugeHandles.add(
            meter.gaugeBuilder("jacoco.coverage.classes.ratio")
                .setDescription("JaCoCo class coverage ratio (covered / total classes)")
                .setUnit("1")
                .buildWithCallback(new Consumer<ObservableDoubleMeasurement>() {
                    public void accept(ObservableDoubleMeasurement m) {
                        CoverageSummary s = coverageCache.get();
                        if (s != null) m.record(s.classes, TOTAL_ATTRS);
                    }
                })
        );

        JacocoOtelAgent.log("Registered 5 OTel gauge metrics (jacoco.coverage.*.ratio)");
    }
}

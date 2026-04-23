package io.jacoco.otel;

/**
 * Thread-safe cache for the latest {@link CoverageSummary}.
 *
 * <p>The OTel gauge callbacks may be invoked by the SDK's metric reader at any time.
 * Rather than running a potentially expensive JaCoCo read + classpath analysis on every
 * callback invocation, the callbacks return the last cached value. A background thread
 * in {@link CoverageMetricsReporter} refreshes the cache at the configured interval.
 */
public class CoverageCache {

    private volatile CoverageSummary summary;
    private volatile long lastUpdatedMs = 0;

    public CoverageCache() {
    }

    /** Store the latest coverage result. */
    public synchronized void update(CoverageSummary summary) {
        this.summary      = summary;
        this.lastUpdatedMs = System.currentTimeMillis();
    }

    /**
     * Returns the last cached summary, or {@code null} if no data has been collected yet.
     * Callers should guard against {@code null}.
     */
    public CoverageSummary get() {
        return summary;
    }

    /** Returns true if no data has been collected yet. */
    public boolean isEmpty() {
        return summary == null;
    }

    /** Wall-clock time of the last successful update, or 0 if never updated. */
    public long lastUpdatedMs() {
        return lastUpdatedMs;
    }
}

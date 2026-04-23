package io.jacoco.otel;

/**
 * Snapshot of overall code coverage ratios in the range [0.0, 1.0].
 *
 * <p>All values represent {@code covered / (covered + missed)} for each coverage type.
 * A value of 0.0 means no coverage data (either 0 covered or 0 total).
 */
public class CoverageSummary {

    public final double instructions;
    public final double branches;
    public final double lines;
    public final double methods;
    public final double classes;

    public CoverageSummary(double instructions, double branches, double lines,
                           double methods, double classes) {
        this.instructions = instructions;
        this.branches     = branches;
        this.lines        = lines;
        this.methods      = methods;
        this.classes      = classes;
    }

    /** A zero-coverage summary used when no data is available. */
    public static CoverageSummary empty() {
        return new CoverageSummary(0, 0, 0, 0, 0);
    }

    @Override
    public String toString() {
        return String.format(
            "Coverage[instructions=%.1f%%, branches=%.1f%%, lines=%.1f%%, methods=%.1f%%, classes=%.1f%%]",
            instructions * 100, branches * 100, lines * 100, methods * 100, classes * 100
        );
    }
}

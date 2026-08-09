package com.example.control_plane;

import java.util.ArrayList;
import java.util.List;

/**
 * Computed latency distribution statistics over a sliding window of raw
 * response-time samples gathered from Redis aggregates.
 *
 * <p>Percentiles use the <em>nearest-rank</em> method: for a window of N sorted
 * samples the p-th percentile is the value at index {@code ceil(p/100 * N)}.
 * This replaces the previous hard-coded mock values in {@code SlaValidatorService}.</p>
 */
public final class AggregatedMetrics {

    public static final int[] PERCENTILES = {50, 95, 99};

    private final long sampleCount;
    private final double min;
    private final double max;
    private final double avg;
    private final double p50;
    private final double p95;
    private final double p99;
    private final double errorRate;
    private final long totalRequests;
    private final long timestamp;

    private AggregatedMetrics(long sampleCount, double min, double max, double avg,
                              double p50, double p95, double p99,
                              double errorRate, long totalRequests, long timestamp) {
        this.sampleCount = sampleCount;
        this.min = min;
        this.max = max;
        this.avg = avg;
        this.p50 = p50;
        this.p95 = p95;
        this.p99 = p99;
        this.errorRate = errorRate;
        this.totalRequests = totalRequests;
        this.timestamp = timestamp;
    }

    /**
     * Computes an {@link AggregatedMetrics} from raw latency samples using the
     * nearest-rank percentile method. The error rate is the errored requests over
     * the total requests reported by the load generator (falling back to
     * {@code samples + errors} when no explicit total is available). An empty
     * window yields a zeroed aggregate.
     */
    public static AggregatedMetrics fromSamples(List<Long> latencies, long errorCount,
                                                long totalRequests, long timestamp) {
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);

        long count = sorted.size();
        long errors = Math.max(0, errorCount);
        // Every request in the window is either a sample or an error; prefer the
        // explicit total the load generator publishes when present.
        long total = Math.max(0, totalRequests) > 0 ? Math.max(0, totalRequests) : count + errors;

        if (count == 0) {
            double errorRate = total > 0 ? (double) errors / total : 0.0;
            return new AggregatedMetrics(0, 0, 0, 0, 0, 0, 0, errorRate, total, timestamp);
        }

        double min = sorted.get(0);
        double max = sorted.get((int) (count - 1));

        double sum = 0;
        for (long latency : sorted) {
            sum += latency;
        }
        double avg = sum / count;

        return new AggregatedMetrics(
                count, min, max, avg,
                nearestRank(sorted, 50),
                nearestRank(sorted, 95),
                nearestRank(sorted, 99),
                total > 0 ? (double) errors / total : 0.0,
                total, timestamp);
    }

    private static double nearestRank(List<Long> sorted, int percentile) {
        double rank = Math.ceil((percentile / 100.0) * sorted.size());
        return sorted.get((int) Math.max(1, rank) - 1);
    }

    public long getSampleCount() { return sampleCount; }
    public double getMin() { return min; }
    public double getMax() { return max; }
    public double getAvg() { return avg; }
    public double getP50() { return p50; }
    public double getP95() { return p95; }
    public double getP99() { return p99; }
    public double getErrorRate() { return errorRate; }
    public long getTotalRequests() { return totalRequests; }
    public long getTimestamp() { return timestamp; }
}
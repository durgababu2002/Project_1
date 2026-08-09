package com.example.control_plane;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * In-memory sliding window of raw latency samples as they stream in from Redis
 * aggregates. Samples older than {@code windowMs} are evicted so percentile math
 * always reflects a recent, fixed-duration measurement period.
 *
 * <p>Error counts are tracked <em>per incoming batch</em> and expire together
 * with the samples they belong to, so the error rate stays windowed rather than
 * accumulating over the lifetime of the load test.</p>
 */
@Component
public class AggregateWindow {

    private record Sample(long timestamp, long latency) {
    }

    /**
     * One inbound Redis aggregate batch. Carries the errored / total request
     * counts observed by the load generator so they can be rolled out of the
     * window alongside the latency samples.
     */
    private record Batch(long timestamp, long errorCount, long totalRequests) {
    }

    private final List<Sample> samples = new ArrayList<>();
    private final List<Batch> batches = new ArrayList<>();
    private final long windowMs;

    public AggregateWindow() {
        this(30_000L);
    }

    public AggregateWindow(long windowMs) {
        this.windowMs = windowMs;
    }

    /** Appends one batch of raw latency samples (with their observed error counts). */
    public synchronized void addBatch(List<Long> latencies, long batchErrorCount,
                                      long batchTotalRequests, long now) {
        long current = now > 0 ? now : System.currentTimeMillis();
        if (batchErrorCount > 0 || batchTotalRequests > 0) {
            batches.add(new Batch(current, batchErrorCount, batchTotalRequests));
        }
        for (Long latency : latencies) {
            if (latency != null) {
                samples.add(new Sample(current, latency));
            }
        }
        evict(current);
    }

    private void evict(long now) {
        long cutoff = now - windowMs;
        Iterator<Sample> sampleIt = samples.iterator();
        while (sampleIt.hasNext()) {
            if (sampleIt.next().timestamp() < cutoff) {
                sampleIt.remove();
            } else {
                break;
            }
        }
        Iterator<Batch> batchIt = batches.iterator();
        while (batchIt.hasNext()) {
            if (batchIt.next().timestamp() < cutoff) {
                batchIt.remove();
            } else {
                break;
            }
        }
    }

    /** Computes percentile statistics over the current window contents. */
    public synchronized AggregatedMetrics aggregate() {
        List<Long> latencies = new ArrayList<>(samples.size());
        for (Sample s : samples) {
            latencies.add(s.latency);
        }
        long errorCount = 0;
        long totalRequests = 0;
        for (Batch b : batches) {
            errorCount += b.errorCount();
            totalRequests += b.totalRequests();
        }
        return AggregatedMetrics.fromSamples(latencies, errorCount, totalRequests,
                System.currentTimeMillis());
    }

    public synchronized int currentSampleCount() {
        return samples.size();
    }
}
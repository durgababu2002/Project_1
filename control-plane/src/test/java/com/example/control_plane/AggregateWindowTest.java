package com.example.control_plane;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the sliding-window semantics of {@link AggregateWindow}: both latency
 * samples and the error counts they carry must expire together so the SLA
 * engine always evaluates a recent, fixed-duration measurement period.
 */
class AggregateWindowTest {

    @Test
    void retainsFreshSamplesAndPercentilesWithinWindow() {
        AggregateWindow window = new AggregateWindow(30_000L);
        window.addBatch(range(1, 100), 0, 100, 1_000L);

        AggregatedMetrics m = window.aggregate();
        assertThat(m.getSampleCount()).isEqualTo(100);
        assertThat(m.getP50()).isEqualTo(50.0);
        assertThat(m.getP95()).isEqualTo(95.0);
        assertThat(m.getP99()).isEqualTo(99.0);
        assertThat(m.getMax()).isEqualTo(100.0);
    }

    @Test
    void expiresOldSamplesAndTheirErrorCountsTogether() {
        long t0 = 1_000_000L;
        AggregateWindow window = new AggregateWindow(30_000L);

        // Batch 1 falls entirely outside the window (t0 -> t0+35s later).
        window.addBatch(range(1, 100), 25, 125, t0);
        // Batch 2 is still inside the window: one healthy sample at t0+35s.
        window.addBatch(List.of(50L), 0, 1, t0 + 35_000L);

        AggregatedMetrics m = window.aggregate();

        // Only the fresh sample remains...
        assertThat(m.getSampleCount()).isEqualTo(1);
        assertThat(m.getMin()).isEqualTo(50.0);
        assertThat(m.getP99()).isEqualTo(50.0);
        // ...and the expired errors must not pollute the error rate anymore.
        assertThat(m.getErrorRate()).isZero();
    }

    @Test
    void freshErrorsCountTowardsWindowedErrorRate() {
        AggregateWindow window = new AggregateWindow(30_000L);
        window.addBatch(List.of(100L), 3, 10, System.currentTimeMillis());

        AggregatedMetrics m = window.aggregate();
        assertThat(m.getErrorRate()).isEqualTo(0.3);
    }

    @Test
    void emptyWindowYieldsZeroedAggregate() {
        AggregatedMetrics m = new AggregateWindow().aggregate();
        assertThat(m.getSampleCount()).isZero();
        assertThat(m.getP99()).isZero();
        assertThat(m.getErrorRate()).isZero();
    }

    private List<Long> range(int from, int to) {
        List<Long> out = new ArrayList<>(to - from + 1);
        for (int i = from; i <= to; i++) {
            out.add((long) i);
        }
        return out;
    }
}
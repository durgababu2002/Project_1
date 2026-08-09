package com.example.control_plane;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatedMetricsTest {

    // 1..100 gives predictable nearest-rank percentiles.
    private final List<Long> oneToHundred = new java.util.ArrayList<>();
    {
        for (long i = 1; i <= 100; i++) oneToHundred.add(i);
    }

    @Test
    void computesNearestRankPercentiles() {
        AggregatedMetrics m = AggregatedMetrics.fromSamples(
                oneToHundred, 0, 100, 1_000L);

        assertThat(m.getSampleCount()).isEqualTo(100);
        assertThat(m.getMin()).isEqualTo(1.0);
        assertThat(m.getMax()).isEqualTo(100.0);
        assertThat(m.getAvg()).isEqualTo(50.5);
        // ceil(50/100*100)=50 -> value at index 49 = 50 ; ceil(95/100*100)=95;
        // ceil(99/100*100)=99
        assertThat(m.getP50()).isEqualTo(50.0);
        assertThat(m.getP95()).isEqualTo(95.0);
        assertThat(m.getP99()).isEqualTo(99.0);
    }

    @Test
    void emptyWindowYieldsZeroedAggregate() {
        AggregatedMetrics m = AggregatedMetrics.fromSamples(List.of(), 0, 0, 0L);
        assertThat(m.getSampleCount()).isZero();
        assertThat(m.getP99()).isZero();
        assertThat(m.getAvg()).isZero();
    }

    @Test
    void errorRateIsErroredRequestsOverTotalAttempted() {
        // 1 error out of 4 attempted requests.
        AggregatedMetrics m = AggregatedMetrics.fromSamples(
                Arrays.asList(10L, 20L, 30L), 1, 4, 0L);
        assertThat(m.getErrorRate()).isEqualTo(1.0 / 4.0);
    }

    @Test
    void errorRateUsesExplicitTotalNotDoubleCountingErrors() {
        // The locust pipeline emits one latency sample per request and reports an
        // explicit total, so 10 failed out of 100 attempted is exactly 10%.
        List<Long> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) samples.add(200L);
        AggregatedMetrics m = AggregatedMetrics.fromSamples(samples, 10, 100, 0L);
        assertThat(m.getErrorRate()).isEqualTo(0.10);

        // Errors with no reported total fall back to samples + errors = 100%.
        AggregatedMetrics fallback = AggregatedMetrics.fromSamples(List.of(), 3, 0, 0L);
        assertThat(fallback.getErrorRate()).isEqualTo(1.0);
    }

    @Test
    void singleSampleReflectsItselfAtAllPercentiles() {
        AggregatedMetrics m = AggregatedMetrics.fromSamples(List.of(450L), 0, 1, 0L);
        assertThat(m.getMin()).isEqualTo(450.0);
        assertThat(m.getMax()).isEqualTo(450.0);
        assertThat(m.getP50()).isEqualTo(450.0);
        assertThat(m.getP99()).isEqualTo(450.0);
    }
}
package com.example.control_plane;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SlaValidatorServiceTest {

    private SlaValidatorService validator;
    private LoadTest test;

    @BeforeEach
    void setUp() {
        // isolate the pure decision logic; the "disabled" hosts mean no remote calls
        validator = new SlaValidatorService("disabled", "disabled");
        test = new LoadTest();
        test.setStatus("RUNNING");
        test.setSlaMaxP99Latency(500.0);
        test.setSlaMaxErrorRate(1.0);
    }

    private AggregatedMetrics metrics(Long... latencies) {
        return AggregatedMetrics.fromSamples(Arrays.asList(latencies), 0,
                latencies.length, System.currentTimeMillis());
    }

    @Test
    void p99OverLimitTriggersBreach() {
        // p99 of 1..100 is 99ms, under the 500ms limit -> no breach
        assertThat(validator.evaluate(test, rangeMetrics(1, 100))).isNull();

        // push p99 well over limit
        test.setSlaMaxP99Latency(50.0);
        assertThat(validator.evaluate(test, rangeMetrics(1, 100)))
                .contains("p99");
    }

    @Test
    void errorRateOverLimitTriggersBreach() {
        test.setSlaMaxErrorRate(0.5);
        AggregatedMetrics highErrors = AggregatedMetrics.fromSamples(
                Arrays.asList(100L, 120L, 110L), 5, 8, 0L); // 62.5% errors
        assertThat(validator.evaluate(test, highErrors)).contains("error rate");
    }

    @Test
    void withinSlaReturnsNoBreach() {
        assertThat(validator.evaluate(test, rangeMetrics(1, 100))).isNull();
    }

    private AggregatedMetrics rangeMetrics(int from, int to) {
        Long[] latencies = new Long[to - from + 1];
        for (int i = from; i <= to; i++) {
            latencies[i - from] = (long) i;
        }
        return metrics(latencies);
    }
}
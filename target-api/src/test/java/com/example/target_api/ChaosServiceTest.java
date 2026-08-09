package com.example.target_api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the Week 3 chaos fault engine: time-boxed experiment injection,
 * blast-radius scoping, resource side effects, and automatic self-recovery.
 */
class ChaosServiceTest {

    private ChaosService chaos;

    @BeforeEach
    void setUp() {
        chaos = new ChaosService(new SimpleMeterRegistry());
    }

    private ChaosSpec spec(String type, int durationSeconds, String scope) {
        ChaosSpec s = new ChaosSpec();
        s.setType(type);
        s.setDurationSeconds(durationSeconds);
        s.setScope(scope);
        return s;
    }

    private ChaosSpec latency(int durationSeconds, int delayMs, String scope) {
        ChaosSpec s = spec(ChaosService.NETWORK_LATENCY, durationSeconds, scope);
        s.setDelayMs(delayMs);
        return s;
    }

    @Test
    void injectRegistersActiveFault() {
        chaos.inject(latency(30, 300, "ALL"));
        assertThat(chaos.status().get("faults_active")).isEqualTo(1);
    }

    @Test
    void networkLatencyRespectsBlastRadius() throws InterruptedException {
        // 300ms delay scoped to GET only.
        chaos.inject(latency(60_000, 300, "GET"));

        long start = System.currentTimeMillis();
        chaos.applyChaos("POST"); // out of scope -> no sleep
        long postElapsed = System.currentTimeMillis() - start;
        assertThat(postElapsed).isLessThan(200);

        chaos.applyChaos("GET"); // in scope -> sleeps ~300ms
        long getElapsed = System.currentTimeMillis() - start - postElapsed;
        assertThat(getElapsed).isGreaterThanOrEqualTo(200);
    }

    @Test
    void http5xxAlwaysThrowsWhenRateIs100() {
        ChaosSpec spec = spec(ChaosService.HTTP_5XX, 60_000, "ALL");
        spec.setErrorRatePercent(100);
        chaos.inject(spec);

        assertThatThrownBy(() -> chaos.applyChaos("GET"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(500);
    }

    @Test
    void http5xxNeverThrowsWhenRateIs0() {
        ChaosSpec spec = spec(ChaosService.HTTP_5XX, 60_000, "ALL");
        spec.setErrorRatePercent(0);
        chaos.inject(spec);

        assertThatCode(() -> chaos.applyChaos("GET")).doesNotThrowAnyException();
    }

    @Test
    void http4xxAlwaysThrowsClientErrorsAtRate100() {
        ChaosSpec spec = spec(ChaosService.HTTP_4XX, 60_000, "ALL");
        spec.setRatePercent(100);
        chaos.inject(spec);

        assertThatThrownBy(() -> chaos.applyChaos("GET"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    int code = ((ResponseStatusException) e).getStatusCode().value();
                    assertThat(code == 400 || code == 404 || code == 429).isTrue();
                });
    }
@Test
    void dbPoolExhaustionReturns503BeyondPoolSize() throws Exception {
        ChaosSpec spec = spec(ChaosService.DB_POOL_EXHAUSTION, 60_000, "ALL");
        spec.setPoolSize(1);
        spec.setQueryMs(300);
        chaos.inject(spec);

        // First request acquires the single permit and holds it for one "query".
        Thread holder = new Thread(() -> {
            try {
                chaos.applyChaos("GET");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        Thread.sleep(100);

        // Second concurrent request must be starved out -> 503.
        assertThatThrownBy(() -> chaos.applyChaos("GET"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(503);

        holder.join();
    }

    @Test
    void memorySpikeAllocatesAndReleasesOnExpiry() throws InterruptedException {
        ChaosSpec spec = spec(ChaosService.MEMORY_SPIKE, 1, "ALL");
        spec.setMemoryMb(4);
        chaos.inject(spec);

        assertThat(chaos.status().get("memory_allocated_bytes"))
                .isEqualTo(4L * 1024 * 1024);

        Thread.sleep(1_200); // outlive the 1s time-box
        chaos.expireFaults();

        assertThat(chaos.status().get("memory_allocated_bytes")).isEqualTo(0L);
        assertThat(chaos.status().get("faults_active")).isEqualTo(0);
    }

    @Test
    void cpuSpikeStartsBurnerAndResetStopsIt() throws InterruptedException {
        chaos.inject(spec(ChaosService.CPU_SPIKE, 60_000, "ALL"));

        // The burner thread starts asynchronously.
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline
                && !Boolean.TRUE.equals(chaos.status().get("cpu_burning"))) {
            Thread.sleep(50);
        }
        assertThat(chaos.status().get("cpu_burning")).isEqualTo(true);

        chaos.reset();
        assertThat(chaos.status().get("cpu_burning")).isEqualTo(false);
        assertThat(chaos.status().get("faults_active")).isEqualTo(0);
    }

    @Test
    void clearTypeRemovesOnlyRequestedFault() {
        chaos.inject(latency(10, 100, "ALL"));
        ChaosSpec err = spec(ChaosService.HTTP_4XX, 60_000, "ALL");
        err.setRatePercent(100);
        chaos.inject(err);

        chaos.clearType(ChaosService.NETWORK_LATENCY);

        assertThat(chaos.status().get("faults_active")).isEqualTo(1);
        assertThatThrownBy(() -> chaos.applyChaos("GET"))
                .isInstanceOf(ResponseStatusException.class); // 4xx still active
    }

    @Test
    void resetClearsEverything() {
        ChaosSpec spec = spec(ChaosService.HTTP_5XX, 60_000, "ALL");
        spec.setErrorRatePercent(100);
        chaos.inject(spec);
        chaos.reset();

        assertThat(chaos.status().get("faults_active")).isEqualTo(0);
        assertThatCode(() -> chaos.applyChaos("GET")).doesNotThrowAnyException();
    }

    @Test
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> chaos.inject(spec("SELF_DESTRUCT", 10, "ALL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown chaos type");
    }
}
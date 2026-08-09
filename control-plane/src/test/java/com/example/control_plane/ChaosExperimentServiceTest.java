package com.example.control_plane;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the chaos game-day orchestrator: launch validation + failure
 * handling, self-expiry completion, and SLA-breach rollback semantics.
 * All remote HTTP (target API) is pointed at an unroutable port and every
 * network error is swallowed by design, exactly like the SLA engine.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChaosExperimentServiceTest {

    private static final String DEAD_TARGET = "http://localhost:1";

    @Mock
    private ChaosExperimentRepository repository;

    @Mock
    private AggregateWindow aggregateWindow;

    private ChaosExperimentService service;

    @BeforeEach
    void setUp() {
        service = new ChaosExperimentService(repository, aggregateWindow, DEAD_TARGET);
        when(aggregateWindow.aggregate())
                .thenReturn(AggregatedMetrics.fromSamples(List.of(100L), 0, 1, 1L));
        when(repository.save(any(ChaosExperiment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ExperimentLaunchRequest launchRequest() {
        ExperimentLaunchRequest req = new ExperimentLaunchRequest();
        req.setName("Latency blast");
        req.setType("NETWORK_LATENCY");
        req.setDurationSeconds(30);
        req.setScope("GET");
        // bogusKey must be filtered out: only library-defined keys are forwarded.
        req.setIntensity(Map.of("delayMs", 500, "bogusKey", 123));
        return req;
    }

    private ChaosExperiment runningExp(Long id) {
        ChaosExperiment exp = new ChaosExperiment();
        exp.setId(id);
        exp.setStatus("RUNNING");
        return exp;
    }

    @Test
    void libraryExposesAllFaultTypes() {
        assertThat(service.library().keySet()).containsExactlyInAnyOrder(
                "NETWORK_LATENCY", "HTTP_5XX", "HTTP_4XX",
                "CPU_SPIKE", "MEMORY_SPIKE", "DB_POOL_EXHAUSTION");
    }

    @Test
    void launchAgainstUnreachableTargetMarksFailedButPersists() {
        ChaosExperiment exp = service.launch(launchRequest());

        assertThat(exp.getStatus()).isEqualTo("FAILED");
        assertThat(exp.getType()).isEqualTo("NETWORK_LATENCY");
        assertThat(exp.getScope()).isEqualTo("GET");
        assertThat(exp.getStartedAt()).isNotNull();
        assertThat(exp.getNote()).containsPattern("Target API unreachable");
        // Before-snapshot + sanitized intensity are persisted for the history table.
        assertThat(exp.getMetricsBeforeJson()).contains("\"sample_count\"");
        assertThat(exp.getConfigJson()).contains("\"delayMs\":500");
        assertThat(exp.getConfigJson()).doesNotContain("bogusKey");
    }

    @Test
    void launchRejectsUnknownType() {
        ExperimentLaunchRequest req = launchRequest();
        req.setType("SELF_DESTRUCT");
        assertThatThrownBy(() -> service.launch(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown chaos experiment type");
    }

    @Test
    void completeExpiredRunsCompletesTimedOutExperiments() {
        ChaosExperiment overdue = runningExp(1L);
        overdue.setDurationSeconds(10);
        overdue.setStartedAt(System.currentTimeMillis() - 60_000);
        when(repository.findByStatus("RUNNING")).thenReturn(List.of(overdue));

        service.completeExpiredRuns();

        assertThat(overdue.getStatus()).isEqualTo("COMPLETED");
        assertThat(overdue.getEndedAt()).isNotNull();
        assertThat(overdue.getMetricsAfterJson()).contains("\"sample_count\"");
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(overdue);
    }

    @Test
    void completeExpiredRunsSkipsRunsInsideTheirTimeBox() {
        ChaosExperiment stillRunning = runningExp(2L);
        stillRunning.setDurationSeconds(3600);
        stillRunning.setStartedAt(System.currentTimeMillis());
        when(repository.findByStatus("RUNNING")).thenReturn(List.of(stillRunning));

        service.completeExpiredRuns();

        assertThat(stillRunning.getStatus()).isEqualTo("RUNNING");
        verify(repository, never()).save(any(ChaosExperiment.class));
    }

    @Test
    void rollbackAllRunningRollsBackEveryRunAndCounts() {
        ChaosExperiment a = runningExp(1L);
        ChaosExperiment b = runningExp(2L);
        when(repository.findByStatus("RUNNING")).thenReturn(List.of(a, b));

        int count = service.rollbackAllRunning("SLA breach auto-remediation");

        assertThat(count).isEqualTo(2);
        assertThat(a.getStatus()).isEqualTo("ROLLED_BACK");
        assertThat(b.getStatus()).isEqualTo("ROLLED_BACK");
        assertThat(b.getNote()).isEqualTo("SLA breach auto-remediation");
        assertThat(b.getEndedAt()).isNotNull();
    }

    @Test
    void stopRollsBackOnlyRunningAndNoOpsForCompleted() {
        ChaosExperiment running = runningExp(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(running));
        ChaosExperiment stopped = service.stop(1L).orElseThrow();
        assertThat(stopped.getStatus()).isEqualTo("ROLLED_BACK");

        ChaosExperiment alreadyDone = new ChaosExperiment();
        alreadyDone.setId(2L);
        alreadyDone.setStatus("COMPLETED");
        when(repository.findById(2L)).thenReturn(Optional.of(alreadyDone));
        ChaosExperiment untouched = service.stop(2L).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void stopReturnsEmptyWhenExperimentMissing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThat(service.stop(99L)).isEmpty();
    }
}
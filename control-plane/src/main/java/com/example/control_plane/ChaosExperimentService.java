package com.example.control_plane;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Week 3 — chaos game-day orchestrator on the control plane.
 *
 * <p>Launches time-boxed chaos experiments against the Target API, persists
 * every run (before/after SLA snapshots, status) to Postgres, auto-completes
 * runs whose time-box expires (<b>self-expiry on the platform too</b>), and
 * aggressively rolls back all running experiments — both on explicit stop and
 * as part of {@code SLA_BREACH} auto-remediation.</p>
 */
@Service
public class ChaosExperimentService {

    /** The on-demand experiment library definitions surfaced to the dashboard. */
    static final Map<String, Map<String, Object>> LIBRARY = Map.ofEntries(
            Map.entry("NETWORK_LATENCY", Map.of(
                    "name", "Network Latency",
                    "description", "Injects artificial delay (ms) on every in-scope request. Fails latency SLAs.",
                    "defaultDurationSeconds", 30,
                    "intensityKeys", List.of("delayMs"),
                    "defaults", Map.of("delayMs", 800))),
            Map.entry("HTTP_5XX", Map.of(
                    "name", "HTTP 5xx Errors",
                    "description", "Returns 500 for the given percentage of in-scope requests. Fails error-rate SLAs.",
                    "defaultDurationSeconds", 30,
                    "intensityKeys", List.of("errorRatePercent"),
                    "defaults", Map.of("errorRatePercent", 100))),
            Map.entry("HTTP_4XX", Map.of(
                    "name", "Random HTTP 4xx",
                    "description", "Returns random 400/404/429 for the given percentage of in-scope requests. Tests client error handling.",
                    "defaultDurationSeconds", 30,
                    "intensityKeys", List.of("ratePercent"),
                    "defaults", Map.of("ratePercent", 60))),
            Map.entry("CPU_SPIKE", Map.of(
                    "name", "CPU Spike",
                    "description", "Burns CPU on a background thread until recovery. Watch SLO margin erode without hard failure.",
                    "defaultDurationSeconds", 45,
                    "intensityKeys", List.of(),
                    "defaults", Map.of())),
            Map.entry("MEMORY_SPIKE", Map.of(
                    "name", "Memory Spike",
                    "description", "Allocates and pins extra heap (MB) until recovery.",
                    "defaultDurationSeconds", 45,
                    "intensityKeys", List.of("memoryMb"),
                    "defaults", Map.of("memoryMb", 200))),
            Map.entry("DB_POOL_EXHAUSTION", Map.of(
                    "name", "DB Pool Exhaustion",
                    "description", "Starvation failure: pool of poolSize permits, queryMs per query; overload gets 503.",
                    "defaultDurationSeconds", 30,
                    "intensityKeys", List.of("poolSize", "queryMs"),
                    "defaults", Map.of("poolSize", 5, "queryMs", 300))));

    private final ChaosExperimentRepository repository;
    private final AggregateWindow aggregateWindow;
    private final String targetApiUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChaosExperimentService(
            ChaosExperimentRepository repository,
            AggregateWindow aggregateWindow,
            @Value("${target-api.url:http://localhost:8081}") String targetApiUrl) {
        this.repository = repository;
        this.aggregateWindow = aggregateWindow;
        this.targetApiUrl = targetApiUrl;
    }

    /** Game-day library: which experiments the dashboard may launch. */
    public Map<String, Map<String, Object>> library() {
        return LIBRARY;
    }

    public List<ChaosExperiment> listAll() {
        return repository.findAllByOrderByStartedAtDesc();
    }

    public Optional<ChaosExperiment> find(Long id) {
        return repository.findById(id);
    }

    /**
     * Persists a new RUNNING experiment, snapshots SLA metrics <em>before</em>,
     * then delegates the actual injection to the Target API. If the Target API
     * cannot be reached the experiment is marked {@code FAILED} instead of
     * pretending chaos happened.
     */
    public ChaosExperiment launch(ExperimentLaunchRequest request) {
        String type = request.getType() == null ? "" : request.getType().trim().toUpperCase();
        if (!LIBRARY.containsKey(type)) {
            throw new IllegalArgumentException("Unknown chaos experiment type: " + request.getType());
        }

        long now = System.currentTimeMillis();
        ChaosExperiment exp = new ChaosExperiment();
        exp.setName(displayName(type, request.getName()));
        exp.setType(type);
        exp.setScope(normalizeScope(request.getScope()));
        exp.setDurationSeconds(Math.max(1, request.getDurationSeconds()));
        exp.setStatus("RUNNING");
        exp.setStartedAt(now);
        exp.setConfigJson(toJson(filterIntensity(type, request.getIntensity())));
        exp.setMetricsBeforeJson(snapshotJson());
        ChaosExperiment saved = repository.save(exp);

        if (!injectOnTarget(saved)) {
            saved.setStatus("FAILED");
            saved.setEndedAt(System.currentTimeMillis());
            saved.setNote("Target API unreachable - chaos was not injected; check target-api service");
            return repository.save(saved);
        }
        return saved;
    }
/**
     * Auto-completes RUNNING experiments whose time-box has elapsed. This is
     * the platform-side counterpart of the Target API's self-expiry sweeper.
     */
    @Scheduled(fixedRateString = "${chaos.completion-interval-ms:5000}")
    public synchronized void completeExpiredRuns() {
        long now = System.currentTimeMillis();
        for (ChaosExperiment exp : repository.findByStatus("RUNNING")) {
            if (exp.getStartedAt() + exp.getDurationSeconds() * 1000L <= now) {
                exp.setStatus("COMPLETED");
                exp.setEndedAt(now);
                exp.setMetricsAfterJson(snapshotJson());
                repository.save(exp);
                System.out.println("[CHAOS] Experiment " + exp.getId() + " (" + exp.getType()
                        + ") auto-completed after time-box expiry");
            }
        }
    }

    /**
     * Immediately stops every RUNNING experiment (state {@code ROLLED_BACK}) and
     * resets all faults on the Target API. Called by the SLA engine during
     * {@code SLA_BREACH} auto-remediation; returns how many runs were stopped.
     */
    public synchronized int rollbackAllRunning(String note) {
        List<ChaosExperiment> running = repository.findByStatus("RUNNING");
        for (ChaosExperiment exp : running) {
            rollback(exp, note == null || note.isBlank() ? "Rolled back" : note);
        }
        return running.size();
    }

    /** Rolls back a single running experiment; safely no-ops for finished ones. */
    public synchronized Optional<ChaosExperiment> stop(Long id) {
        return repository.findById(id).map(exp -> {
            if ("RUNNING".equals(exp.getStatus())) {
                rollback(exp, "Stopped manually");
            }
            return exp;
        });
    }

    private void rollback(ChaosExperiment exp, String note) {
        long now = System.currentTimeMillis();
        exp.setStatus("ROLLED_BACK");
        exp.setEndedAt(now);
        exp.setMetricsAfterJson(snapshotJson());
        exp.setNote(note);
        repository.save(exp);
        try {
            restTemplate.postForEntity(targetApiUrl + "/api/products/chaos/reset", null, String.class);
            System.out.println("[CHAOS] Reset Target API faults for experiment " + exp.getId());
        } catch (Exception e) {
            System.err.println("[CHAOS] Target reset failed for experiment " + exp.getId() + ": " + e.getMessage());
        }
    }

    /** POSTs the experiment spec to the Target API; false when it cannot be reached. */
    private boolean injectOnTarget(ChaosExperiment exp) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("type", exp.getType());
            body.put("durationSeconds", exp.getDurationSeconds());
            body.put("scope", exp.getScope());
            try {
                Map<String, Object> intensity =
                        objectMapper.readValue(exp.getConfigJson(), new TypeReference<>() { });
                body.putAll(intensity);
            } catch (JacksonException ignored) {
                // configJson was written by us; treat parse failure as empty intensity
            }
            restTemplate.postForEntity(targetApiUrl + "/api/products/chaos/experiment", body, String.class);
            return true;
        } catch (Exception e) {
            System.err.println("[CHAOS] Target API injection failed: " + e.getMessage());
            return false;
        }
    }
// ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Integer> filterIntensity(String type, Map<String, Integer> intensity) {
        Map<String, Integer> filtered = new LinkedHashMap<>();
        if (intensity != null) {
            List<String> allowed = intensityKeys(type);
            for (String key : allowed) {
                Integer value = intensity.get(key);
                if (value != null) {
                    filtered.put(key, value);
                }
            }
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private List<String> intensityKeys(String type) {
        Object raw = LIBRARY.get(type).get("intensityKeys");
        return raw == null ? List.of() : (List<String>) raw;
    }

    private String displayName(String type, String requestedName) {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName.trim();
        }
        Object rawName = LIBRARY.get(type).get("name");
        return rawName == null ? type : rawName.toString();
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "ALL";
        }
        String normalized = scope.trim().toUpperCase();
        return (normalized.equals("GET") || normalized.equals("POST")) ? normalized : "ALL";
    }

    private String snapshotJson() {
        AggregatedMetrics m = aggregateWindow.aggregate();
        if (m == null) {
            return "{}";
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("sample_count", m.getSampleCount());
        snap.put("avg", m.getAvg());
        snap.put("p50", m.getP50());
        snap.put("p95", m.getP95());
        snap.put("p99", m.getP99());
        snap.put("max", m.getMax());
        snap.put("error_rate", m.getErrorRate());
        snap.put("timestamp", m.getTimestamp());
        return toJson(snap);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return "{}";
        }
    }
}
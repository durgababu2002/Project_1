package com.example.control_plane;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real SLA engine. Periodically evaluates every running test against the
 * true latency percentiles and error rate computed from Redis aggregates
 * (see {@link AggregateWindow}) rather than the previous hard-coded mock.
 *
 * <p>On breach it performs the full remediation loop automatically:
 * <ol>
 *   <li>marks the test {@code FAILED_SLA} and persists the breach reason,</li>
 *   <li>stops the Locust load test via the Controller Web API,</li>
 *   <li>rolls back injected chaos on the Target API,</li>
 *   <li>fires an outbound webhook alert, and</li>
 *   <li>pushes a live {@code /topic/alerts} update to dashboards.</li>
 * </ol>
 * </p>
 */
@Service
public class SlaValidatorService {

    private static final String AUTO_STOP_DISABLED = "disabled";
    public static final String ACTION_AUTO_STOP = "AUTO_STOP";
    public static final String ACTION_ROLLBACK = "ROLLBACK";
    public static final String ACTION_WEBHOOK = "WEBHOOK";

    @Autowired
    private LoadTestRepository loadTestRepository;

    @Autowired
    private AggregateWindow aggregateWindow;

    @Autowired
    private WebhookAlertService webhookAlertService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ChaosExperimentService chaosExperimentService;

    private final RestTemplate restTemplate = new RestTemplate();

    private final String locustHost;
    private final String targetApiHost;

    // Per-test breach guard so we remediate exactly once.
    private final Map<Long, Boolean> breachedTests = new ConcurrentHashMap<>();

    @Value("${sla.validator.enabled:true}")
    private boolean enabled;

    public SlaValidatorService(
            @Value("${locust.host:http://localhost:8089}") String locustHost,
            @Value("${target-api.url:http://localhost:8081}") String targetApiHost) {
        this.locustHost = locustHost;
        this.targetApiHost = targetApiHost;
    }

    @Scheduled(fixedRateString = "${sla.validator.interval-ms:5000}")
    public void validateRunningTests() {
        if (!enabled) {
            return;
        }

        List<LoadTest> runningTests = loadTestRepository.findAll().stream()
                .filter(t -> "RUNNING".equals(t.getStatus()))
                .toList();

        for (LoadTest test : runningTests) {
            if (Boolean.TRUE.equals(breachedTests.get(test.getId()))) {
                continue;
            }

            AggregatedMetrics metrics = aggregateWindow.aggregate();
            // Empty window = no data yet; skip rather than alarm spuriously.
            if (metrics.getSampleCount() == 0 && metrics.getTotalRequests() == 0) {
                continue;
            }

            String breachReason = evaluate(test, metrics);
            if (breachReason == null) {
                continue;
            }

            remediate(test, metrics, breachReason);
        }
    }

    /** Returns a human-readable breach reason, or {@code null} if not breached. */
    String evaluate(LoadTest test, AggregatedMetrics metrics) {
        if (test.getSlaMaxP99Latency() > 0 && metrics.getP99() > test.getSlaMaxP99Latency()) {
            return String.format("p99 (%.1f ms) exceeded SLA limit (%.1f ms)",
                    metrics.getP99(), test.getSlaMaxP99Latency());
        }
        double errorRatePercent = metrics.getErrorRate() * 100.0;
        if (test.getSlaMaxErrorRate() > 0 && errorRatePercent > test.getSlaMaxErrorRate()) {
            return String.format("error rate (%.2f%%) exceeded SLA limit (%.2f%%)",
                    errorRatePercent, test.getSlaMaxErrorRate());
        }
        return null;
    }

    private synchronized void remediate(LoadTest test, AggregatedMetrics metrics, String reason) {
        if (Boolean.TRUE.equals(breachedTests.putIfAbsent(test.getId(), Boolean.TRUE))) {
            return;
        }

        long now = System.currentTimeMillis();
        test.setStatus("FAILED_SLA");
        test.setBreachReason(reason);
        loadTestRepository.save(test);

        stopLocust(test);
        rollbackChaos(test);
        // Week 3: also stop every running chaos experiment (game days count too).
        int rolledBackExperiments = chaosExperimentService.rollbackAllRunning(
                "SLA_BREACH auto-remediation for test " + test.getId());
        webhookAlertService.sendBreachAlert(test.getId(), test.getName(), reason, metrics, now);

        Map<String, Object> alert = new HashMap<>();
        alert.put("type", "SLA_BREACH");
        alert.put("testId", test.getId());
        alert.put("testName", test.getName());
        alert.put("reason", reason);
        alert.put("timestamp", now);
        alert.put("actions", List.of(ACTION_AUTO_STOP, ACTION_ROLLBACK, ACTION_WEBHOOK));
        alert.put("chaosExperimentsStopped", rolledBackExperiments);
        alert.put("metrics", Map.of(
                "p50", metrics.getP50(),
                "p95", metrics.getP95(),
                "p99", metrics.getP99(),
                "min", metrics.getMin(),
                "max", metrics.getMax(),
                "avg", metrics.getAvg(),
                "errorRate", metrics.getErrorRate()));
        messagingTemplate.convertAndSend("/topic/alerts", (Object) alert);

        System.out.println("[SLA] Test " + test.getId() + " breached: " + reason);
    }

    private void stopLocust(LoadTest test) {
        if (locustHost.equalsIgnoreCase(AUTO_STOP_DISABLED)) {
            return;
        }
        try {
            restTemplate.getForEntity(locustHost + "/stop", String.class);
            System.out.println("[SLA] Auto-stopped Locust swarm for test " + test.getId());
        } catch (Exception e) {
            System.err.println("[SLA] Auto-stop failed: " + e.getMessage());
        }
    }

    /** Rolls back any injected chaos (delay / error rate) on the Target API. */
    private void rollbackChaos(LoadTest test) {
        if (targetApiHost.equalsIgnoreCase(AUTO_STOP_DISABLED)) {
            return;
        }
        try {
            restTemplate.postForEntity(targetApiHost + "/api/products/chaos/reset", null, String.class);
            System.out.println("[SLA] Rolled back chaos injection on Target API for test " + test.getId());
        } catch (Exception e) {
            System.err.println("[SLA] Chaos rollback failed: " + e.getMessage());
        }
    }
}
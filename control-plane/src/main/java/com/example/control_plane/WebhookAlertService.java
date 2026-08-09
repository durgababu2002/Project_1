package com.example.control_plane;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Delivers platform-driven SLA breach alerts to an external webhook (e.g. Slack,
 * Teams, n8n, PagerDuty adapter) over HTTP POST. Dispatches fire-and-forget so a
 * slow or unreachable webhook never blocks the SLA engine.
 */
@Service
public class WebhookAlertService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${sla.alert.webhook-url:}")
    private String webhookUrl;

    private final boolean enabled;

    public WebhookAlertService(
            @Value("${sla.alert.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.enabled = webhookUrl != null && !webhookUrl.isBlank();
    }

    /**
     * Posts a breach alert. Returns {@code true} when a webhook is configured and
     * the notification was accepted, {@code false} when disabled or on failure.
     */
    public boolean sendBreachAlert(Long testId, String testName, String reason,
                                   AggregatedMetrics metrics, long breachedAt) {
        if (!enabled) {
            return false;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = Map.of(
                    "event", "SLA_BREACH",
                    "test_id", testId,
                    "test_name", testName,
                    "reason", reason,
                    "breached_at", breachedAt,
                    "metrics", Map.of(
                            "p50", metrics.getP50(),
                            "p95", metrics.getP95(),
                            "p99", metrics.getP99(),
                            "min", metrics.getMin(),
                            "max", metrics.getMax(),
                            "avg", metrics.getAvg(),
                            "error_rate", metrics.getErrorRate())
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(webhookUrl, request, String.class);
            return true;
        } catch (Exception e) {
            System.err.println("Webhook alert delivery failed: " + e.getMessage());
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
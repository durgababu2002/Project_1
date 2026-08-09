package com.example.control_plane;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Surfaces the latest SLA-relevant latency distribution computed by the SLA
 * engine. Used to verify the real percentile math and to power a live status
 * panel on the dashboard.
 */
@RestController
@RequestMapping("/api/sla")
@CrossOrigin(origins = "*")
public class SlaStatusController {

    @Autowired
    private AggregateWindow aggregateWindow;

    @GetMapping("/status")
    public Map<String, Object> slaStatus() {
        AggregatedMetrics m = aggregateWindow.aggregate();
        return Map.of(
                "sample_count", m.getSampleCount(),
                "min", m.getMin(),
                "max", m.getMax(),
                "avg", m.getAvg(),
                "p50", m.getP50(),
                "p95", m.getP95(),
                "p99", m.getP99(),
                "error_rate", m.getErrorRate(),
                "total_requests", m.getTotalRequests(),
                "timestamp", m.getTimestamp());
    }
}
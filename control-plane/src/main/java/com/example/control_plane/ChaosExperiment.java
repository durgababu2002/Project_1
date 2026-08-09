package com.example.control_plane;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * Week 3 — persisted record of one on-demand chaos game day run. Steps through
 * RUNNING -> COMPLETED (self-expiry), ROLLED_BACK (SLA breach auto-remediation
 * or manual stop) or FAILED (launch itself failed).
 *
 * <p>Keeps before/after SLA metrics snapshots so the dashboard can show the
 * blast radius on the real SLA in both directions (headroom before, breach
 * during, state after).</p>
 */
@Entity
public class ChaosExperiment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String type;              // NETWORK_LATENCY | HTTP_5XX | HTTP_4XX | CPU_SPIKE | MEMORY_SPIKE | DB_POOL_EXHAUSTION
    private String scope;             // ALL | GET | POST
    private int durationSeconds;

    @Column(length = 1000)
    private String configJson;        // intensity parameters sent to the target API

    private String status;            // RUNNING, COMPLETED, ROLLED_BACK, FAILED
    private Long startedAt;
    private Long endedAt;

    @Column(length = 1000)
    private String metricsBeforeJson;

    @Column(length = 1000)
    private String metricsAfterJson;

    private boolean slaBreached;
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
    public Long getEndedAt() { return endedAt; }
    public void setEndedAt(Long endedAt) { this.endedAt = endedAt; }
    public String getMetricsBeforeJson() { return metricsBeforeJson; }
    public void setMetricsBeforeJson(String metricsBeforeJson) { this.metricsBeforeJson = metricsBeforeJson; }
    public String getMetricsAfterJson() { return metricsAfterJson; }
    public void setMetricsAfterJson(String metricsAfterJson) { this.metricsAfterJson = metricsAfterJson; }
    public boolean isSlaBreached() { return slaBreached; }
    public void setSlaBreached(boolean slaBreached) { this.slaBreached = slaBreached; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
package com.example.control_plane;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class LoadTest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    private String status; // PENDING, RUNNING, COMPLETED, FAILED_SLA
    private int targetUsers;
    private int spawnRate;
    private double slaMaxP99Latency;
    private double slaMaxErrorRate;
    private String breachReason;
    private Long startedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTargetUsers() { return targetUsers; }
    public void setTargetUsers(int targetUsers) { this.targetUsers = targetUsers; }
    public int getSpawnRate() { return spawnRate; }
    public void setSpawnRate(int spawnRate) { this.spawnRate = spawnRate; }
    public double getSlaMaxP99Latency() { return slaMaxP99Latency; }
    public void setSlaMaxP99Latency(double slaMaxP99Latency) { this.slaMaxP99Latency = slaMaxP99Latency; }
    public double getSlaMaxErrorRate() { return slaMaxErrorRate; }
    public void setSlaMaxErrorRate(double slaMaxErrorRate) { this.slaMaxErrorRate = slaMaxErrorRate; }
    public String getBreachReason() { return breachReason; }
    public void setBreachReason(String breachReason) { this.breachReason = breachReason; }
    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }
}

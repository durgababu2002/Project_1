package com.example.control_plane;

import java.util.HashMap;
import java.util.Map;

/**
 * Dashboard payload for launching a chaos experiment: which fault type from
 * the game-day library, for how long, at which intensity, and with what blast
 * radius (which HTTP methods are in scope).
 */
public class ExperimentLaunchRequest {

    private String name;
    private String type;
    private int durationSeconds = 30;
    private String scope = "ALL";
    private Map<String, Integer> intensity = new HashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Map<String, Integer> getIntensity() { return intensity; }
    public void setIntensity(Map<String, Integer> intensity) { this.intensity = intensity; }
}
package com.example.target_api;

/**
 * Describes a single, time-boxed chaos injection request. The {@link ChaosService}
 * turns it into an active fault that **automatically recovers** once
 * {@code durationSeconds} elapses (self-expiry), scoped by {@code scope}.
 *
 * <p>Supported {@code type} values (the "on-demand chaos experiment library"):
 * <ul>
 *   <li>{@code NETWORK_LATENCY}  - inject {@code delayMs} of delay per in-scope request</li>
 *   <li>{@code HTTP_5XX}         - throw 5xx for {@code errorRatePercent}% of in-scope requests</li>
 *   <li>{@code HTTP_4XX}         - throw a random 4xx for {@code ratePercent}% of in-scope requests</li>
 *   <li>{@code CPU_SPIKE}        - busy-burn CPU on a background thread until recovery</li>
 *   <li>{@code MEMORY_SPIKE}     - allocate and hold {@code memoryMb} of heap until recovery</li>
 *   <li>{@code DB_POOL_EXHAUSTION}- exhaust a {@code poolSize}-permit pool; requests wait
 *        {@code queryMs} per "query" and receive 503 when no permit is free</li>
 * </ul>
 */
public class ChaosSpec {

    private String type;
    private int durationSeconds = 30;
    private String scope = "ALL";          // ALL | GET | POST

    // Intensity parameters (only the ones relevant to `type` are read).
    private Integer delayMs;
    private Integer errorRatePercent;      // 5xx
    private Integer ratePercent;           // 4xx
    private Integer burnMs;                // cpu
    private Integer memoryMb;
    private Integer poolSize;
    private Integer queryMs;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Integer getDelayMs() { return delayMs; }
    public void setDelayMs(Integer delayMs) { this.delayMs = delayMs; }
    public Integer getErrorRatePercent() { return errorRatePercent; }
    public void setErrorRatePercent(Integer errorRatePercent) { this.errorRatePercent = errorRatePercent; }
    public Integer getRatePercent() { return ratePercent; }
    public void setRatePercent(Integer ratePercent) { this.ratePercent = ratePercent; }
    public Integer getBurnMs() { return burnMs; }
    public void setBurnMs(Integer burnMs) { this.burnMs = burnMs; }
    public Integer getMemoryMb() { return memoryMb; }
    public void setMemoryMb(Integer memoryMb) { this.memoryMb = memoryMb; }
    public Integer getPoolSize() { return poolSize; }
    public void setPoolSize(Integer poolSize) { this.poolSize = poolSize; }
    public Integer getQueryMs() { return queryMs; }
    public void setQueryMs(Integer queryMs) { this.queryMs = queryMs; }
}
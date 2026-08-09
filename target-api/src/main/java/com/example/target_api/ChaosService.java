package com.example.target_api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Week 3 — the "on-demand chaos experiment" fault engine.
 *
 * <p>Replaces the two ad-hoc injection flags in {@link MockController} with a
 * time-boxed fault library: NETWORK_LATENCY, HTTP_5XX, HTTP_4XX, CPU_SPIKE,
 * MEMORY_SPIKE, DB_POOL_EXHAUSTION.
 *
 * <p>Every fault carries a <em>blast radius</em> ({@code scope}: ALL, GET or
 * POST) and an <em>expiry</em>. A {@link Scheduled} sweeper automatically
 * expires faults once their time-box elapses so chaos self-heals
 * (<b>auto-recovery</b>) without an external actor.
 */
@Service
public class ChaosService {

    public static final String NETWORK_LATENCY = "NETWORK_LATENCY";
    public static final String HTTP_5XX = "HTTP_5XX";
    public static final String HTTP_4XX = "HTTP_4XX";
    public static final String CPU_SPIKE = "CPU_SPIKE";
    public static final String MEMORY_SPIKE = "MEMORY_SPIKE";
    public static final String DB_POOL_EXHAUSTION = "DB_POOL_EXHAUSTION";

    /** One active, time-boxed fault. Expired faults are skipped and swept. */
    record ActiveFault(String type, Map<String, Integer> params, String scope, long activeUntil) {
        boolean isExpired(long now) {
            return now > activeUntil;
        }

        /** Blast radius: only fires for in-scope HTTP methods. */
        boolean appliesTo(String method) {
            return method == null || "ALL".equalsIgnoreCase(scope)
                    || scope.equalsIgnoreCase(method);
        }

        int param(String key, int defaultValue) {
            return params.getOrDefault(key, defaultValue);
        }
    }

    private final List<ActiveFault> faults = new CopyOnWriteArrayList<>();

    private final AtomicInteger faultsActive = new AtomicInteger(0);
    private final AtomicBoolean cpuRunning = new AtomicBoolean(false);
    private volatile Thread cpuBurnerThread;
    private volatile byte[] memoryChunk;
    private final Object memoryLock = new Object();
    private volatile Semaphore dbPool;

    private Random random = new Random();

    public ChaosService(MeterRegistry meterRegistry) {
        registerGauges(meterRegistry);
    }

    private void registerGauges(MeterRegistry meterRegistry) {
        Gauge.builder("chaos_faults_active", this, ChaosService::gaugeFaultsActive)
                .description("Number of currently active chaos faults")
                .register(meterRegistry);
        Gauge.builder("chaos_injected_delay_ms", this, ChaosService::gaugeDelayMs)
                .description("Currently injected artificial delay in milliseconds")
                .register(meterRegistry);
        Gauge.builder("chaos_error_rate_percent", this, ChaosService::gauge5xxRate)
                .description("Currently injected 5xx error rate percentage")
                .register(meterRegistry);
        Gauge.builder("chaos_4xx_rate_percent", this, ChaosService::gauge4xxRate)
                .description("Currently injected random 4xx rate percentage")
                .register(meterRegistry);
        Gauge.builder("chaos_errors_active", this, ChaosService::gaugeErrorsActive)
                .description("Whether 5xx error injection is currently active (0/1)")
                .register(meterRegistry);
        Gauge.builder("chaos_cpu_busy", this, ChaosService::gaugeCpuBusy)
                .description("Whether the CPU spike burner is running (0/1)")
                .register(meterRegistry);
        Gauge.builder("chaos_memory_allocated_bytes", this, ChaosService::gaugeMemoryBytes)
                .description("Heap bytes held by the currently injected memory spike")
                .register(meterRegistry);
        Gauge.builder("chaos_db_pool_available", this, ChaosService::gaugePoolAvailable)
                .description("Available DB pool permits (-1 when no pool fault active)")
                .register(meterRegistry);
    }
/**
     * Turns a {@link ChaosSpec} into an active time-boxed fault and starts any
     * side effects (CPU burner / memory allocation). Returns a summary of what
     * was applied for observability and history persistence.
     */
    public synchronized Map<String, Object> inject(ChaosSpec spec) {
        if (spec == null || spec.getType() == null || spec.getType().isBlank()) {
            throw new IllegalArgumentException("Chaos experiment type is required");
        }
        String type = spec.getType().trim().toUpperCase();
        int durationSeconds = Math.max(1, spec.getDurationSeconds());
        long until = System.currentTimeMillis() + durationSeconds * 1000L;
        String scope = normalizeScope(spec.getScope());

        Map<String, Integer> params = new HashMap<>();
        switch (type) {
            case NETWORK_LATENCY -> params.put("delayMs", positive(spec.getDelayMs(), 800));
            case HTTP_5XX -> params.put("errorRatePercent", clampRate(spec.getErrorRatePercent()));
            case HTTP_4XX -> params.put("ratePercent", clampRate(spec.getRatePercent()));
            case CPU_SPIKE -> params.put("burnMs", positive(spec.getBurnMs(), 500));
            case MEMORY_SPIKE -> params.put("memoryMb", positive(spec.getMemoryMb(), 200));
            case DB_POOL_EXHAUSTION -> {
                params.put("poolSize", positive(spec.getPoolSize(), 5));
                params.put("queryMs", positive(spec.getQueryMs(), 300));
            }
            default -> throw new IllegalArgumentException("Unknown chaos type: " + type);
        }

        ActiveFault fault = new ActiveFault(type, Map.copyOf(params), scope, until);
        faults.add(fault);
        faultsActive.set(faults.size());
        startCpuBurnerIfNeeded();
        applyMemoryAllocationIfNeeded();

        return faultSummary(fault);
    }

    /** Applies all active, in-scope faults for one request. */
    public void applyChaos(String method) throws InterruptedException {
        long now = System.currentTimeMillis();
        for (ActiveFault fault : faults) {
            if (fault.isExpired(now) || !fault.appliesTo(method)) {
                continue;
            }
            switch (fault.type()) {
                case NETWORK_LATENCY -> Thread.sleep(fault.param("delayMs", 0));
                case DB_POOL_EXHAUSTION -> applyPoolExhaustion(fault);
                case HTTP_5XX -> maybeThrow(fault, HttpStatus.INTERNAL_SERVER_ERROR,
                        fault.param("errorRatePercent", 100));
                case HTTP_4XX -> maybeThrow4xx(fault);
                // CPU_SPIKE is handled by the background burner thread.
                default -> { }
            }
        }
    }

    /** Immediately clears every active fault and its side effects. */
    public synchronized void reset() {
        faults.clear();
        faultsActive.set(0);
        cpuRunning.set(false);
        stopMemoryChunk();
        dbPool = null;
    }

    /** Removes faults of one type only (used by the legacy direct endpoints). */
    public synchronized void clearType(String type) {
        faults.removeIf(f -> f.type().equalsIgnoreCase(type));
        faultsActive.set(faults.size());
        if (!isCpuFaultActive()) {
            cpuRunning.set(false);
        }
        if (!isMemoryFaultActive()) {
            stopMemoryChunk();
        }
    }

    /** Observability snapshot of currently active faults. */
    public Map<String, Object> status() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> active = faults.stream()
                .filter(f -> !f.isExpired(now))
                .map(this::faultSummary)
                .toList();
        return Map.of(
                "faults_active", active.size(),
                "faults", active,
                "cpu_burning", isCpuFaultActive(),
                "memory_allocated_bytes", memoryChunk == null ? 0L : (long) memoryChunk.length,
                "db_pool_available", dbPool == null ? -1 : dbPool.availablePermits());
    }

    boolean isCpuFaultActive() {
        long now = System.currentTimeMillis();
        return faults.stream().anyMatch(f -> CPU_SPIKE.equals(f.type()) && !f.isExpired(now));
    }

    boolean isMemoryFaultActive() {
        long now = System.currentTimeMillis();
        return faults.stream().anyMatch(f -> MEMORY_SPIKE.equals(f.type()) && !f.isExpired(now));
    }
// ------------------------------------------------------------------
    // Auto-recovery sweeper
    // ------------------------------------------------------------------

    @Scheduled(fixedRate = 1000)
    public synchronized void expireFaults() {
        long now = System.currentTimeMillis();
        faults.removeIf(f -> f.isExpired(now));
        faultsActive.set(faults.size());
        if (!isCpuFaultActive()) {
            cpuRunning.set(false);
        }
        if (!isMemoryFaultActive()) {
            stopMemoryChunk();
        }
        if (!isPoolFaultActive()) {
            dbPool = null;
        }
    }

    // ------------------------------------------------------------------
    // Fault mechanics
    // ------------------------------------------------------------------

    private void applyPoolExhaustion(ActiveFault fault) throws InterruptedException {
        Semaphore pool = dbPool;
        int poolSize = fault.param("poolSize", 5);
        if (pool == null || pool.availablePermits() > poolSize) {
            dbPool = pool = new Semaphore(poolSize);
        }
        // Simulate a connection-pool acquire: no free permit -> 503 starvation.
        if (!pool.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "DB connection pool exhausted (chaos injection)");
        }
        try {
            // Simulate the DB query holding the connection for queryMs.
            Thread.sleep(fault.param("queryMs", 300));
        } finally {
            pool.release();
        }
    }

    private void maybeThrow(ActiveFault fault, HttpStatus status, int ratePercent) {
        if (ratePercent >= 100 || random.nextDouble() * 100 < ratePercent) {
            throw new ResponseStatusException(status, "Chaos Engineering " + fault.type() + " Injection");
        }
    }

    private void maybeThrow4xx(ActiveFault fault) {
        int rate = fault.param("ratePercent", 100);
        if (rate < 100 && random.nextDouble() * 100 >= rate) {
            return;
        }
        int roll = random.nextInt(3);
        HttpStatus status = switch (roll) {
            case 0 -> HttpStatus.BAD_REQUEST;
            case 1 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.TOO_MANY_REQUESTS;
        };
        throw new ResponseStatusException(status, "Chaos Engineering random 4xx injection");
    }

    private void startCpuBurnerIfNeeded() {
        if (isCpuFaultActive() && cpuRunning.compareAndSet(false, true)) {
            cpuBurnerThread = new Thread(this::cpuBurnLoop, "chaos-cpu-burner");
            cpuBurnerThread.setDaemon(true);
            cpuBurnerThread.start();
        }
    }

    private void cpuBurnLoop() {
        while (cpuRunning.get() && isCpuFaultActive()) {
            long sliceEnd = System.currentTimeMillis() + 25;
            double x = 1.000001;
            while (System.currentTimeMillis() < sliceEnd) {
                x = Math.sqrt(x * 1.000001 + 1.0);
            }
        }
        cpuRunning.set(false);
    }

    private void applyMemoryAllocationIfNeeded() {
        if (!isMemoryFaultActive()) {
            return;
        }
        long mb = 0;
        long now = System.currentTimeMillis();
        for (ActiveFault f : faults) {
            if (MEMORY_SPIKE.equals(f.type()) && !f.isExpired(now)) {
                mb = Math.max(mb, f.param("memoryMb", 200));
            }
        }
        synchronized (memoryLock) {
            memoryChunk = new byte[(int) (mb * 1024 * 1024)];
        }
    }

    private void stopMemoryChunk() {
        synchronized (memoryLock) {
            memoryChunk = null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean isPoolFaultActive() {
        long now = System.currentTimeMillis();
        return faults.stream().anyMatch(f -> DB_POOL_EXHAUSTION.equals(f.type()) && !f.isExpired(now));
    }

    private Map<String, Object> faultSummary(ActiveFault fault) {
        return Map.of(
                "type", fault.type(),
                "scope", fault.scope(),
                "expiresAt", fault.activeUntil(),
                "secondsRemaining", Math.max(0,
                        (fault.activeUntil() - System.currentTimeMillis()) / 1000),
                "params", fault.params());
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "ALL";
        }
        String normalized = scope.trim().toUpperCase();
        return switch (normalized) {
            case "GET", "POST" -> normalized;
            default -> "ALL";
        };
    }

    private static int positive(Integer value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(1, value);
    }

    private static int clampRate(Integer value) {
        if (value == null) {
            return 100;
        }
        return Math.max(0, Math.min(100, value));
    }

    /** Test seam: deterministic random for rate-based faults. */
    void setRandomSource(Random source) {
        this.random = source;
    }

    // ------------------------------------------------------------------
    // Micrometer gauge suppliers
    // ------------------------------------------------------------------

    private double gaugeFaultsActive() { return faultsActive.get(); }
    private double gaugeDelayMs() {
        return faults.stream().filter(f -> NETWORK_LATENCY.equals(f.type()))
                .mapToInt(f -> f.param("delayMs", 0)).max().orElse(0);
    }
    private double gauge5xxRate() {
        return faults.stream().filter(f -> HTTP_5XX.equals(f.type()))
                .mapToInt(f -> f.param("errorRatePercent", 0)).max().orElse(0);
    }
    private double gauge4xxRate() {
        return faults.stream().filter(f -> HTTP_4XX.equals(f.type()))
                .mapToInt(f -> f.param("ratePercent", 0)).max().orElse(0);
    }
    private double gaugeErrorsActive() {
        long now = System.currentTimeMillis();
        return faults.stream().anyMatch(f -> HTTP_5XX.equals(f.type()) && !f.isExpired(now)) ? 1.0 : 0.0;
    }
    private double gaugeCpuBusy() { return cpuRunning.get() && isCpuFaultActive() ? 1.0 : 0.0; }
    private double gaugeMemoryBytes() { return memoryChunk == null ? 0 : memoryChunk.length; }
    private double gaugePoolAvailable() { return dbPool == null ? -1 : dbPool.availablePermits(); }

    @PreDestroy
    public void shutdown() {
        cpuRunning.set(false);
    }
}
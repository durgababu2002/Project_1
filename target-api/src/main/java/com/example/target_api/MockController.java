package com.example.target_api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = "*")
public class MockController {

    private final List<Product> products = new CopyOnWriteArrayList<>();
    
    // Chaos configurations
    private final AtomicInteger delayMs = new AtomicInteger(0);
    private final AtomicBoolean throwErrors = new AtomicBoolean(false);
    private final AtomicInteger errorRatePercent = new AtomicInteger(0);

    public MockController(MeterRegistry meterRegistry) {
        // Register custom chaos gauges so the injected fault state is observable
        // in Prometheus / Grafana (see monitoring/grafana dashboards).
        Gauge.builder("chaos_injected_delay_ms", delayMs, AtomicInteger::get)
            .description("Currently injected artificial delay in milliseconds")
            .register(meterRegistry);
        Gauge.builder("chaos_error_rate_percent", errorRatePercent, AtomicInteger::get)
            .description("Currently injected error rate percentage")
            .register(meterRegistry);
        Gauge.builder("chaos_errors_active", throwErrors, b -> b.get() ? 1.0 : 0.0)
            .description("Whether error injection is currently active (0/1)")
            .register(meterRegistry);

        // Initialize with some dummy data
        for (int i = 0; i < 5; i++) {
            Product p = new Product();
            p.setId(UUID.randomUUID().toString());
            p.setName("Product " + i);
            p.setPrice(10.0 + i);
            products.add(p);
        }
    }

    @GetMapping
    public ResponseEntity<List<Product>> getProducts() throws InterruptedException {
        applyChaos();
        return ResponseEntity.ok(products);
    }

    @PostMapping
    public ResponseEntity<Product> createProduct(@RequestBody Product product) throws InterruptedException {
        applyChaos();
        product.setId(UUID.randomUUID().toString());
        products.add(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable String id) throws InterruptedException {
        applyChaos();
        return products.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Chaos configuration endpoints
    @PostMapping("/chaos/delay")
    public ResponseEntity<String> setDelay(@RequestParam int ms) {
        delayMs.set(ms);
        return ResponseEntity.ok("Delay set to " + ms + "ms");
    }

    @PostMapping("/chaos/errors")
    public ResponseEntity<String> setErrors(@RequestParam boolean active, @RequestParam(defaultValue = "100") int ratePercent) {
        throwErrors.set(active);
        errorRatePercent.set(ratePercent);
        return ResponseEntity.ok("Errors active: " + active + ", rate: " + ratePercent + "%");
    }

    private void applyChaos() throws InterruptedException {
        int currentDelay = delayMs.get();
        if (currentDelay > 0) {
            Thread.sleep(currentDelay);
        }

        if (throwErrors.get()) {
            int rate = errorRatePercent.get();
            if (rate >= 100 || Math.random() * 100 < rate) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Chaos Engineering Error Injection");
            }
        }
    }

    public static class Product {
        private String id;
        private String name;
        private double price;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getPrice() { return price; }
        public void setPrice(double price) { this.price = price; }
    }
}

package com.example.target_api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mock target microservice with Week 3 fault-injection capabilities. All chaos
 * state lives in {@link ChaosService} (time-boxed, self-recovering, scoped);
 * this controller only wires HTTP access to it.
 */
@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = "*")
public class MockController {

    private static final int MAX_PRODUCTS = 100;
    private final List<Product> products = new CopyOnWriteArrayList<>();
    private final ChaosService chaosService;

    public MockController(ChaosService chaosService) {
        this.chaosService = chaosService;
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
        chaosService.applyChaos("GET");
        return ResponseEntity.ok(products);
    }

    @PostMapping
    public ResponseEntity<Product> createProduct(@RequestBody Product product) throws InterruptedException {
        chaosService.applyChaos("POST");
        product.setId(UUID.randomUUID().toString());
        while (products.size() >= MAX_PRODUCTS) {
            products.remove(0);
        }
        products.add(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable String id) throws InterruptedException {
        chaosService.applyChaos("GET");
        return products.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------
    // Week 3: on-demand chaos experiment API
    // ------------------------------------------------------------------

    /** Launch a time-boxed fault from the experiment library. */
    @PostMapping("/chaos/experiment")
    public ResponseEntity<Map<String, Object>> runExperiment(@RequestBody ChaosSpec spec) {
        try {
            return ResponseEntity.ok(chaosService.inject(spec));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Observability snapshot of the currently active faults. */
    @GetMapping("/chaos/status")
    public ResponseEntity<Map<String, Object>> chaosStatus() {
        return ResponseEntity.ok(chaosService.status());
    }

    // ------------------------------------------------------------------
    // Legacy Week 1/2 direct endpoints (kept for dashboard sliders + SLA rollback)
    // ------------------------------------------------------------------

    @PostMapping("/chaos/delay")
    public ResponseEntity<String> setDelay(@RequestParam int ms) {
        if (ms <= 0) {
            chaosService.clearType(ChaosService.NETWORK_LATENCY);
            return ResponseEntity.ok("Delay cleared");
        }
        ChaosSpec spec = new ChaosSpec();
        spec.setType(ChaosService.NETWORK_LATENCY);
        spec.setDurationSeconds(86_400); // persistent until reset, like before
        spec.setScope("ALL");
        spec.setDelayMs(ms);
        chaosService.inject(spec);
        return ResponseEntity.ok("Delay set to " + ms + "ms");
    }

    @PostMapping("/chaos/errors")
    public ResponseEntity<String> setErrors(@RequestParam boolean active, @RequestParam(defaultValue = "100") int ratePercent) {
        if (!active || ratePercent <= 0) {
            chaosService.clearType(ChaosService.HTTP_5XX);
            return ResponseEntity.ok("Errors cleared");
        }
        ChaosSpec spec = new ChaosSpec();
        spec.setType(ChaosService.HTTP_5XX);
        spec.setDurationSeconds(86_400); // persistent until reset, like before
        spec.setScope("ALL");
        spec.setErrorRatePercent(ratePercent);
        chaosService.inject(spec);
        return ResponseEntity.ok("Errors active: " + active + ", rate: " + ratePercent + "%");
    }

    @PostMapping("/chaos/reset")
    public ResponseEntity<String> resetChaos() {
        chaosService.reset();
        return ResponseEntity.ok("Chaos injection reset");
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
package com.example.control_plane;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@RestController
@RequestMapping("/api/tests")
@CrossOrigin(origins = "*")
public class LoadTestController {

    @Autowired
    private LoadTestRepository loadTestRepository;

    @Autowired
    private AggregateWindow aggregateWindow;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${locust.host:http://localhost:8089}")
    private String locustHost;

    @Value("${target-api.url:http://localhost:8081}")
    private String targetApiUrl;

    @GetMapping
    public List<LoadTest> getAllTests() {
        return loadTestRepository.findAll();
    }

    @PostMapping("/start")
    public ResponseEntity<LoadTest> startTest(@RequestBody LoadTest testConfig) {
        // Clear previous test residuals from sliding window so old metrics don't pollute this run
        aggregateWindow.clear();

        testConfig.setStatus("RUNNING");
        testConfig.setStartedAt(System.currentTimeMillis());
        testConfig.setBreachReason(null);
        LoadTest saved = loadTestRepository.save(testConfig);
        
        // Trigger Locust swarm via REST API
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
            map.add("user_count", String.valueOf(testConfig.getTargetUsers() > 0 ? testConfig.getTargetUsers() : 10));
            map.add("spawn_rate", String.valueOf(testConfig.getSpawnRate() > 0 ? testConfig.getSpawnRate() : 2));
            map.add("host", targetApiUrl);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);
            restTemplate.postForEntity(locustHost + "/swarm", request, String.class);
            System.out.println("Triggered Locust Swarm for test ID: " + saved.getId());
        } catch (Exception e) {
            System.err.println("Failed to trigger Locust swarm: " + e.getMessage());
        }
        
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<LoadTest> stopTest(@PathVariable Long id) {
        return loadTestRepository.findById(id).map(test -> {
            // Preserve FAILED_SLA status if already breached
            if (!"FAILED_SLA".equals(test.getStatus())) {
                test.setStatus("COMPLETED");
            }
            LoadTest saved = loadTestRepository.save(test);
            
            // Stop Locust swarm via REST API
            try {
                restTemplate.getForEntity(locustHost + "/stop", String.class);
                System.out.println("Stopped Locust Swarm for test ID: " + saved.getId());
            } catch (Exception e) {
                System.err.println("Failed to stop Locust swarm: " + e.getMessage());
            }
            
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/fail_sla")
    public ResponseEntity<LoadTest> failSla(@PathVariable Long id) {
        return loadTestRepository.findById(id).map(test -> {
            test.setStatus("FAILED_SLA");
            LoadTest saved = loadTestRepository.save(test);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }
}

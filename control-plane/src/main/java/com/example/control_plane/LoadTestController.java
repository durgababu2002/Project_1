package com.example.control_plane;

import org.springframework.beans.factory.annotation.Autowired;
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

    private final RestTemplate restTemplate = new RestTemplate();
    // LOCUST_HOST points at the Locust web UI. Inside Docker Compose it must be
    // "http://load-generator:8089"; locally it falls back to localhost.
    private final String locustHost = System.getenv().getOrDefault("LOCUST_HOST", "http://localhost:8089");

    @GetMapping
    public List<LoadTest> getAllTests() {
        return loadTestRepository.findAll();
    }

    @PostMapping("/start")
    public ResponseEntity<LoadTest> startTest(@RequestBody LoadTest testConfig) {
        testConfig.setStatus("RUNNING");
        LoadTest saved = loadTestRepository.save(testConfig);
        
        // Trigger Locust swarm via REST API
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
            map.add("user_count", String.valueOf(testConfig.getTargetUsers() > 0 ? testConfig.getTargetUsers() : 10));
            map.add("spawn_rate", String.valueOf(testConfig.getSpawnRate() > 0 ? testConfig.getSpawnRate() : 2));
            map.add("host", "http://target-api:8081");

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
            test.setStatus("COMPLETED");
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

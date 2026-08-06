package com.example.control_plane;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tests")
@CrossOrigin(origins = "*") // For the dashboard
public class LoadTestController {

    @Autowired
    private LoadTestRepository loadTestRepository;

    @GetMapping
    public List<LoadTest> getAllTests() {
        return loadTestRepository.findAll();
    }

    @PostMapping("/start")
    public ResponseEntity<LoadTest> startTest(@RequestBody LoadTest testConfig) {
        testConfig.setStatus("RUNNING");
        LoadTest saved = loadTestRepository.save(testConfig);
        
        // In a full implementation, this would trigger the Locust Docker container
        // or send a REST call to the Locust Master.
        System.out.println("Started Load Test: " + saved.getId());
        
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<LoadTest> stopTest(@PathVariable Long id) {
        return loadTestRepository.findById(id).map(test -> {
            test.setStatus("COMPLETED");
            LoadTest saved = loadTestRepository.save(test);
            
            // Here we would stop the Locust process
            System.out.println("Stopped Load Test: " + saved.getId());
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

package com.example.control_plane;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SlaValidatorService {

    @Autowired
    private LoadTestRepository loadTestRepository;

    // This method would realistically query Redis or PostgreSQL for the aggregated metrics
    // of the currently RUNNING test, and if they exceed SLA, fail the test.
    
    // @Scheduled(fixedRate = 5000) // Check every 5 seconds
    public void validateRunningTests() {
        List<LoadTest> runningTests = loadTestRepository.findAll().stream()
            .filter(t -> "RUNNING".equals(t.getStatus()))
            .toList();

        for (LoadTest test : runningTests) {
            // Mock logic:
            double currentP99 = 450.0; // Fetch from Redis...
            if (currentP99 > test.getSlaMaxP99Latency()) {
                System.out.println("SLA Breached for test " + test.getId());
                test.setStatus("FAILED_SLA");
                loadTestRepository.save(test);
            }
        }
    }
}

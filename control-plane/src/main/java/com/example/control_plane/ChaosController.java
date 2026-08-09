package com.example.control_plane;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Week 3 — HTTP API for the chaos game-day: the experiment library, run history
 * (persisted), launch, and stop/rollback.
 */
@RestController
@RequestMapping("/api/chaos")
@CrossOrigin(origins = "*")
public class ChaosController {

    @Autowired
    private ChaosExperimentService chaosExperimentService;

    /** The experiment library (names, defaults) the dashboard renders. */
    @GetMapping("/library")
    public Map<String, Map<String, Object>> library() {
        return chaosExperimentService.library();
    }

    @GetMapping("/experiments")
    public List<ChaosExperiment> experiments() {
        return chaosExperimentService.listAll();
    }

    @GetMapping("/experiments/{id}")
    public ResponseEntity<ChaosExperiment> experiment(@PathVariable Long id) {
        return chaosExperimentService.find(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Launch a time-boxed chaos experiment. */
    @PostMapping("/experiments")
    public ResponseEntity<ChaosExperiment> launch(@RequestBody ExperimentLaunchRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(chaosExperimentService.launch(request));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Stop/rollback a single running experiment. */
    @PostMapping("/experiments/{id}/stop")
    public ResponseEntity<ChaosExperiment> stop(@PathVariable Long id) {
        return chaosExperimentService.stop(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
package com.example.control_plane;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChaosExperimentRepository extends JpaRepository<ChaosExperiment, Long> {

    /** Newest runs first, for the game-day history table. */
    List<ChaosExperiment> findAllByOrderByStartedAtDesc();

    List<ChaosExperiment> findByStatus(String status);
}
package com.veenaacharya.agentic.orchestrator.state;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StageAttemptRepository extends JpaRepository<StageAttempt, Long> {

    List<StageAttempt> findByRunIdOrderByIdAsc(String runId);
}

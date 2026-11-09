package com.veenaacharya.agentic.orchestrator.state;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StageExecutionRepository extends JpaRepository<StageExecution, Long> {

    List<StageExecution> findByRunIdOrderByIdAsc(String runId);
}

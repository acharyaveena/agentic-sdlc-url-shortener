package com.veenaacharya.agentic.orchestrator.state;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<Decision, Long> {

    List<Decision> findByRunIdOrderByIdAsc(String runId);
}

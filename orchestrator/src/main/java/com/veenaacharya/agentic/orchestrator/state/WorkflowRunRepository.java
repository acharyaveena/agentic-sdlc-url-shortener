package com.veenaacharya.agentic.orchestrator.state;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {
}

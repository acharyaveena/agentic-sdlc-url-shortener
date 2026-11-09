package com.veenaacharya.agentic.orchestrator.metrics;

import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.StageAttemptRepository;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** Loads run state and computes reliability metrics for all runs or for one run. */
@Component
public class MetricsService {

    private final WorkflowRunRepository runs;
    private final StageExecutionRepository stages;
    private final StageAttemptRepository attempts;
    private final ApprovalRepository approvals;

    public MetricsService(WorkflowRunRepository runs, StageExecutionRepository stages,
                          StageAttemptRepository attempts, ApprovalRepository approvals) {
        this.runs = runs;
        this.stages = stages;
        this.attempts = attempts;
        this.approvals = approvals;
    }

    public MetricsReport forAllRuns() {
        return MetricsCalculator.compute("all runs", runs.findAll(), stages.findAll(), attempts.findAll(),
                approvals.findAll());
    }

    public MetricsReport forRun(String runId) {
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
        return MetricsCalculator.compute("run " + runId, List.of(run), stages.findByRunIdOrderByIdAsc(runId),
                attempts.findByRunIdOrderByIdAsc(runId), approvals.findByRunIdOrderByIdAsc(runId));
    }
}

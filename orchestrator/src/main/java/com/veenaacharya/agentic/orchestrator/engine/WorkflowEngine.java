package com.veenaacharya.agentic.orchestrator.engine;

import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.gate.GateResult;
import com.veenaacharya.agentic.orchestrator.gate.StageGates;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowValidator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * Runs the stage graph. Stages whose dependencies are all satisfied run in parallel on virtual
 * threads; a stage with several dependencies is a join and waits for all of them. A stage that
 * needs approval parks in AWAITING_APPROVAL and the run pauses once nothing else can progress;
 * {@link #resume} continues it after a human decision. All state changes and persistence happen
 * on the calling thread, so workers never touch JPA entities.
 */
@Component
public class WorkflowEngine {

    private final WorkflowDefinition workflow;
    private final List<String> order;
    private final StageExecutor executor;
    private final StageGates gates;
    private final ApprovalService approvals;
    private final WorkflowRunRepository runs;
    private final StageExecutionRepository stageExecutions;
    private final Clock clock;

    public WorkflowEngine(WorkflowDefinition workflow,
                          StageExecutor executor,
                          StageGates gates,
                          ApprovalService approvals,
                          WorkflowRunRepository runs,
                          StageExecutionRepository stageExecutions,
                          Clock clock) {
        this.workflow = workflow;
        this.order = new WorkflowValidator().validate(workflow);
        this.executor = executor;
        this.gates = gates;
        this.approvals = approvals;
        this.runs = runs;
        this.stageExecutions = stageExecutions;
        this.clock = clock;
    }

    public WorkflowRun run(String scenario, ChangeType changeType, String request) {
        WorkflowRun run = runs.save(new WorkflowRun(scenario, changeType, request, clock.instant()));
        for (String stageId : order) {
            stageExecutions.save(new StageExecution(run.getId(), stageId));
        }
        return drive(run.getId());
    }

    /** Continues a paused run after approvals were decided. */
    public WorkflowRun resume(String runId) {
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
        if (run.getStatus() == RunStatus.SUCCEEDED || run.getStatus() == RunStatus.FAILED) {
            throw new IllegalStateException("run " + runId + " already finished: " + run.getStatus());
        }
        return drive(runId);
    }

    private WorkflowRun drive(String runId) {
        WorkflowRun run = runs.findById(runId).orElseThrow();
        Map<String, StageExecution> executions = new LinkedHashMap<>();
        stageExecutions.findByRunIdOrderByIdAsc(runId).forEach(s -> executions.put(s.getStageId(), s));
        run.markRunning();
        run = runs.save(run);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionService<Completion> completions = new ExecutorCompletionService<>(pool);
            int inFlight = 0;
            while (true) {
                inFlight += scheduleReadyStages(run, executions, completions);
                if (inFlight == 0) {
                    break;
                }
                Completion done = awaitNext(completions);
                inFlight--;
                record(executions, done.stageId(), done.result());
            }
        }

        boolean awaiting = executions.values().stream()
                .anyMatch(s -> s.getStatus() == StageStatus.AWAITING_APPROVAL);
        if (awaiting) {
            run.markAwaitingApproval();
            return runs.save(run);
        }
        boolean succeeded = executions.values().stream().allMatch(StageExecution::isSatisfied);
        run.finish(succeeded ? RunStatus.SUCCEEDED : RunStatus.FAILED, clock.instant());
        return runs.save(run);
    }

    private int scheduleReadyStages(WorkflowRun run,
                                    Map<String, StageExecution> executions,
                                    CompletionService<Completion> completions) {
        int started = 0;
        // Topological order means skips and blocks cascade within a single pass.
        for (String stageId : order) {
            StageExecution execution = executions.get(stageId);
            if (execution.getStatus() != StageStatus.PENDING) {
                continue;
            }
            StageDefinition stage = workflow.stage(stageId);

            Optional<String> deadDependency = stage.dependsOn().stream()
                    .filter(dep -> executions.get(dep).isDeadEnd())
                    .findFirst();
            if (deadDependency.isPresent()) {
                execution.block("upstream stage did not succeed: " + deadDependency.get());
                stageExecutions.save(execution);
                continue;
            }
            boolean ready = stage.dependsOn().stream().allMatch(dep -> executions.get(dep).isSatisfied());
            if (!ready) {
                continue;
            }
            if (stage.isSkippedFor(run.getChangeType())) {
                execution.skip(clock.instant());
                stageExecutions.save(execution);
                continue;
            }
            GateResult entry = gates.checkEntry(stageId, executions);
            if (!entry.passed()) {
                execution.block("entry gate failed: " + entry.summary());
                stageExecutions.save(execution);
                continue;
            }

            StageContext context = new StageContext(run.getId(), stage, run.getChangeType(),
                    run.getRequest(), upstreamOutputs(stageId, executions));
            execution.start(clock.instant());
            stageExecutions.save(execution);
            completions.submit(() -> new Completion(stageId, invoke(context)));
            started++;
        }
        return started;
    }

    private Map<String, String> upstreamOutputs(String stageId, Map<String, StageExecution> executions) {
        Set<String> ancestors = workflow.ancestorsOf(stageId);
        Map<String, String> outputs = new LinkedHashMap<>();
        for (String id : order) {
            StageExecution upstream = executions.get(id);
            if (ancestors.contains(id) && upstream.getStatus() == StageStatus.SUCCEEDED) {
                outputs.put(id, upstream.getOutput());
            }
        }
        return outputs;
    }

    private StageResult invoke(StageContext context) {
        try {
            StageResult result = executor.execute(context);
            return result != null ? result : StageResult.failure("executor returned no result");
        } catch (RuntimeException e) {
            return StageResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void record(Map<String, StageExecution> executions, String stageId, StageResult result) {
        StageExecution execution = executions.get(stageId);
        StageDefinition stage = workflow.stage(stageId);
        if (!result.succeeded()) {
            execution.fail(result.error(), clock.instant());
        } else {
            GateResult exit = gates.checkExit(stage, result.output());
            if (!exit.passed()) {
                execution.fail("exit gate failed: " + exit.summary(), clock.instant());
            } else if (stage.approval() != null) {
                execution.awaitApproval(result.output());
                stageExecutions.save(execution);
                approvals.request(execution, stage.approval());
                if (approvals.autoApproves(stage.approval())) {
                    approvals.approve(execution.getRunId(), stageId, ApprovalService.AUTO_ACTOR,
                            "auto-approved (mock mode)");
                    executions.put(stageId, stageExecutions.findById(execution.getId()).orElseThrow());
                }
                return;
            } else {
                execution.succeed(result.output(), clock.instant());
            }
        }
        stageExecutions.save(execution);
    }

    private static Completion awaitNext(CompletionService<Completion> completions) {
        try {
            return completions.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for stages", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("stage worker crashed", e.getCause());
        }
    }

    private record Completion(String stageId, StageResult result) {
    }
}

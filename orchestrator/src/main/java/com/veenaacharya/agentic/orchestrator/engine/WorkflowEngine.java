package com.veenaacharya.agentic.orchestrator.engine;

import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.gate.GateResult;
import com.veenaacharya.agentic.orchestrator.gate.StageGates;
import com.veenaacharya.agentic.orchestrator.recovery.AttemptRecord;
import com.veenaacharya.agentic.orchestrator.recovery.ChaosPlan;
import com.veenaacharya.agentic.orchestrator.recovery.RetryingStageRunner;
import com.veenaacharya.agentic.orchestrator.recovery.RollbackResult;
import com.veenaacharya.agentic.orchestrator.recovery.RollbackStrategy;
import com.veenaacharya.agentic.orchestrator.recovery.StageOutcome;
import com.veenaacharya.agentic.orchestrator.state.ApprovalStatus;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageAttempt;
import com.veenaacharya.agentic.orchestrator.state.StageAttemptRepository;
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
 * threads; a stage with several dependencies is a join and waits for all of them. Each stage gets
 * bounded retries and a fallback model. A stage that needs approval parks in AWAITING_APPROVAL and
 * the run pauses; {@link #resume} continues it after a human decision. If a stage still fails after
 * recovery, no new stages start, running ones finish, the workspace is rolled back to the last
 * approved checkpoint and the run is safe-stopped. All state changes and persistence happen on the
 * calling thread, so workers never touch JPA entities.
 */
@Component
public class WorkflowEngine {

    private final WorkflowDefinition workflow;
    private final List<String> order;
    private final RetryingStageRunner runner;
    private final StageGates gates;
    private final ApprovalService approvals;
    private final RunWorkspace workspace;
    private final RollbackStrategy rollback;
    private final WorkflowRunRepository runs;
    private final StageExecutionRepository stageExecutions;
    private final StageAttemptRepository attempts;
    private final Clock clock;

    public WorkflowEngine(WorkflowDefinition workflow,
                          RetryingStageRunner runner,
                          StageGates gates,
                          ApprovalService approvals,
                          RunWorkspace workspace,
                          RollbackStrategy rollback,
                          WorkflowRunRepository runs,
                          StageExecutionRepository stageExecutions,
                          StageAttemptRepository attempts,
                          Clock clock) {
        this.workflow = workflow;
        this.order = new WorkflowValidator().validate(workflow);
        this.runner = runner;
        this.gates = gates;
        this.approvals = approvals;
        this.workspace = workspace;
        this.rollback = rollback;
        this.runs = runs;
        this.stageExecutions = stageExecutions;
        this.attempts = attempts;
        this.clock = clock;
    }

    public WorkflowRun run(String scenario, ChangeType changeType, String request) {
        return run(scenario, changeType, request, null);
    }

    /** @param chaos failures to inject, e.g. {@code design:2,build-test:always}; null or blank for none */
    public WorkflowRun run(String scenario, ChangeType changeType, String request, String chaos) {
        ChaosPlan plan = ChaosPlan.parse(chaos);
        for (String stageId : plan.stageIds()) {
            if (workflow.stages().stream().noneMatch(s -> s.id().equals(stageId))) {
                throw new IllegalArgumentException("chaos names unknown stage: " + stageId);
            }
        }
        WorkflowRun fresh = new WorkflowRun(scenario, changeType, request, clock.instant());
        fresh.useChaos(plan.spec());
        RunWorkspace.Start start = workspace.startRun(fresh);
        fresh.assignBranch(start.branch());
        fresh.assignBaseCommit(start.baseCommit());
        WorkflowRun run = runs.save(fresh);
        for (String stageId : order) {
            stageExecutions.save(new StageExecution(run.getId(), stageId));
        }
        return drive(run.getId());
    }

    /** Continues a paused run after approvals were decided. */
    public WorkflowRun resume(String runId) {
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
        if (run.isFinished()) {
            throw new IllegalStateException("run " + runId + " already finished: " + run.getStatus());
        }
        return drive(runId);
    }

    private WorkflowRun drive(String runId) {
        WorkflowRun run = runs.findById(runId).orElseThrow();
        workspace.resumeRun(run);
        ChaosPlan chaos = ChaosPlan.parse(run.getChaos());
        Map<String, StageExecution> executions = new LinkedHashMap<>();
        stageExecutions.findByRunIdOrderByIdAsc(runId).forEach(s -> executions.put(s.getStageId(), s));
        run.markRunning();
        run = runs.save(run);

        String failedStage = null;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionService<Completion> completions = new ExecutorCompletionService<>(pool);
            int inFlight = 0;
            while (true) {
                inFlight += scheduleReadyStages(run, executions, completions, chaos, failedStage != null);
                if (inFlight == 0) {
                    break;
                }
                Completion done = awaitNext(completions);
                inFlight--;
                boolean ok = record(executions, done.stageId(), done.outcome());
                if (!ok && failedStage == null) {
                    failedStage = done.stageId();
                }
            }
        }

        if (failedStage != null) {
            return safeStop(run, executions, failedStage);
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
                                    CompletionService<Completion> completions,
                                    ChaosPlan chaos,
                                    boolean stopping) {
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
            if (!ready || stopping) {
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
            completions.submit(() -> new Completion(stageId, runner.run(context, chaos)));
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

    /** Persists the attempts and the stage result; returns false if the stage failed for good. */
    private boolean record(Map<String, StageExecution> executions, String stageId, StageOutcome outcome) {
        StageExecution execution = executions.get(stageId);
        for (AttemptRecord attempt : outcome.attempts()) {
            attempts.save(new StageAttempt(execution.getRunId(), stageId, attempt.number(), attempt.model(),
                    attempt.succeeded(), attempt.error(), attempt.startedAt(), attempt.finishedAt()));
        }
        execution.recordAttempts(Math.max(1, outcome.attempts().size()));
        StageResult result = outcome.result();
        if (!result.succeeded()) {
            execution.fail(result.error(), clock.instant());
            stageExecutions.save(execution);
            return false;
        }
        try {
            execution.recordCommit(workspace.commitStage(
                    execution.getRunId(), stageId, result.output(), result.files()));
        } catch (RuntimeException e) {
            execution.fail("commit failed: " + e.getMessage(), clock.instant());
            stageExecutions.save(execution);
            return false;
        }
        StageDefinition stage = workflow.stage(stageId);
        if (stage.approval() == null) {
            execution.succeed(result.output(), clock.instant());
            stageExecutions.save(execution);
            return true;
        }
        execution.awaitApproval(result.output());
        stageExecutions.save(execution);
        approvals.request(execution, stage.approval());
        if (approvals.autoApproves(stage.approval())) {
            approvals.approve(execution.getRunId(), stageId, ApprovalService.AUTO_ACTOR, "auto-approved (mock mode)");
            executions.put(stageId, stageExecutions.findById(execution.getId()).orElseThrow());
        }
        return true;
    }

    private WorkflowRun safeStop(WorkflowRun run, Map<String, StageExecution> executions, String failedStageId) {
        for (StageExecution execution : executions.values()) {
            if (execution.getStatus() == StageStatus.PENDING) {
                execution.block("run safe-stopped after stage " + failedStageId + " failed");
                stageExecutions.save(execution);
            }
        }
        StageExecution failed = executions.get(failedStageId);
        String reason = "stage " + failedStageId + " failed after " + failed.getAttempt() + " attempt(s): "
                + failed.getError();
        RollbackResult rolledBack;
        try {
            rolledBack = rollback.rollback(run, rollbackTarget(run, executions));
        } catch (RuntimeException e) {
            rolledBack = RollbackResult.NOTHING;
            reason += "; rollback failed, workspace left as is: " + e.getMessage();
        }
        run.safeStop(reason, rolledBack.target(), rolledBack.preservedRef(), clock.instant());
        return runs.save(run);
    }

    /** Commit of the latest stage a human (or allowed auto-approval) signed off, else where the run started. */
    private String rollbackTarget(WorkflowRun run, Map<String, StageExecution> executions) {
        for (int i = order.size() - 1; i >= 0; i--) {
            String stageId = order.get(i);
            StageExecution execution = executions.get(stageId);
            boolean signedOff = workflow.stage(stageId).approval() != null
                    && execution.getStatus() == StageStatus.SUCCEEDED
                    && execution.getCommitSha() != null
                    && approvals.find(run.getId(), stageId)
                    .map(a -> a.getStatus() == ApprovalStatus.APPROVED).orElse(false);
            if (signedOff) {
                return execution.getCommitSha();
            }
        }
        return run.getBaseCommit();
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

    private record Completion(String stageId, StageOutcome outcome) {
    }
}

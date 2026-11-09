package com.veenaacharya.agentic.orchestrator.engine;

import com.veenaacharya.agentic.orchestrator.audit.AuditEvent;
import com.veenaacharya.agentic.orchestrator.audit.AuditLog;
import com.veenaacharya.agentic.orchestrator.audit.Hashes;
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
import com.veenaacharya.agentic.orchestrator.state.Decision;
import com.veenaacharya.agentic.orchestrator.state.DecisionRepository;
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
 * approved checkpoint and the run is safe-stopped.
 *
 * <p>Every stage output is recorded with the hashes of the inputs it was built from. When a re-run
 * stage produces a different output, every downstream stage built from the old one goes STALE and
 * runs again, passing its gates and approvals again. All state changes, persistence and auditing
 * happen on the calling thread, so workers never touch JPA entities.
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
    private final DecisionRepository decisions;
    private final AuditLog audit;
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
                          DecisionRepository decisions,
                          AuditLog audit,
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
        this.decisions = decisions;
        this.audit = audit;
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
        audit.record(AuditEvent.of("RUN_STARTED", run.getId())
                .inputHash(Hashes.sha256(request))
                .actor(AuditLog.ORCHESTRATOR)
                .detail("scenario=" + scenario + "; changeType=" + changeType + "; branch=" + run.getBranch()
                        + (plan.isActive() ? "; chaos=" + plan.spec() : ""))
                .at(clock.instant()));
        return drive(run.getId());
    }

    /** Continues a paused run after approvals were decided or a stage was re-planned. */
    public WorkflowRun resume(String runId) {
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
        if (run.isFinished()) {
            throw new IllegalStateException("run " + runId + " already finished: " + run.getStatus());
        }
        audit.record(AuditEvent.of("RUN_RESUMED", runId).actor(AuditLog.ORCHESTRATOR).at(clock.instant()));
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
            auditRun(run, "RUN_PAUSED", "waiting for approval");
            return runs.save(run);
        }
        boolean succeeded = executions.values().stream().allMatch(StageExecution::isSatisfied);
        run.finish(succeeded ? RunStatus.SUCCEEDED : RunStatus.FAILED, clock.instant());
        auditRun(run, succeeded ? "RUN_SUCCEEDED" : "RUN_FAILED", null);
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
            if (execution.getStatus() != StageStatus.PENDING && execution.getStatus() != StageStatus.STALE) {
                continue;
            }
            StageDefinition stage = workflow.stage(stageId);

            Optional<String> deadDependency = stage.dependsOn().stream()
                    .filter(dep -> executions.get(dep).isDeadEnd())
                    .findFirst();
            if (deadDependency.isPresent()) {
                block(execution, "upstream stage did not succeed: " + deadDependency.get());
                continue;
            }
            // All ancestors, not just direct dependencies: after a re-plan a skipped stage can sit between
            // this stage and an upstream stage that is running again.
            boolean ready = workflow.ancestorsOf(stageId).stream().allMatch(id -> executions.get(id).isSatisfied());
            if (!ready || stopping) {
                continue;
            }
            if (stage.isSkippedFor(run.getChangeType())) {
                execution.skip(clock.instant());
                stageExecutions.save(execution);
                auditStage(execution, "STAGE_SKIPPED", StageStatus.SKIPPED.name(), "not needed for " + run.getChangeType());
                continue;
            }
            GateResult entry = gates.checkEntry(stageId, executions);
            if (!entry.passed()) {
                block(execution, "entry gate failed: " + entry.summary());
                continue;
            }

            Map<String, String> inputs = upstreamOutputs(stageId, executions);
            Map<String, String> upstreamHashes = new LinkedHashMap<>();
            inputs.forEach((id, output) -> upstreamHashes.put(id, executions.get(id).getOutputHash()));
            String feedback = execution.getReplanFeedback();
            execution.recordInputs(Hashes.sha256(run.getRequest() + "\n" + upstreamHashes
                    + (feedback == null ? "" : "\nfeedback: " + feedback)), upstreamHashes);
            execution.start(clock.instant());
            stageExecutions.save(execution);
            audit.record(AuditEvent.of("STAGE_STARTED", run.getId())
                    .stage(stageId, execution.getRevision())
                    .inputHash(execution.getInputHash())
                    .actor(AuditLog.ORCHESTRATOR)
                    .detail(feedback == null ? "inputs=" + upstreamHashes.keySet() : "re-planned: " + feedback)
                    .at(clock.instant()));
            StageContext context = new StageContext(run.getId(), stage, run.getChangeType(), run.getRequest(),
                    inputs, null, feedback);
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
            attempts.save(new StageAttempt(execution.getRunId(), stageId, execution.getRevision(), attempt.number(),
                    attempt.model(), attempt.succeeded(), attempt.error(), attempt.startedAt(), attempt.finishedAt()));
            audit.record(AuditEvent.of(attempt.succeeded() ? "ATTEMPT_SUCCEEDED" : "ATTEMPT_FAILED",
                            execution.getRunId())
                    .stage(stageId, execution.getRevision())
                    .attempt(attempt.number(), attempt.model())
                    .inputHash(execution.getInputHash())
                    .decision(attempt.succeeded() ? "SUCCEEDED" : "FAILED")
                    .actor(attempt.model() == null ? AuditLog.ORCHESTRATOR : AuditLog.AGENT)
                    .detail(attempt.error())
                    .at(attempt.finishedAt()));
        }
        execution.recordAttempts(Math.max(1, outcome.attempts().size()));
        StageResult result = outcome.result();
        if (!result.succeeded()) {
            return failed(execution, result.error());
        }
        try {
            execution.recordCommit(workspace.commitStage(
                    execution.getRunId(), stageId, result.output(), result.files()));
        } catch (RuntimeException e) {
            return failed(execution, "commit failed: " + e.getMessage());
        }
        StageDefinition stage = workflow.stage(stageId);
        if (stage.approval() == null) {
            execution.succeed(result.output(), clock.instant());
        } else {
            execution.awaitApproval(result.output());
        }
        stageExecutions.save(execution);
        String model = outcome.attempts().isEmpty() ? null : outcome.attempts().getLast().model();
        decisions.save(new Decision(execution, model, clock.instant()));
        audit.record(AuditEvent.of(stage.approval() == null ? "STAGE_SUCCEEDED" : "STAGE_AWAITING_APPROVAL",
                        execution.getRunId())
                .stage(stageId, execution.getRevision())
                .attempt(execution.getAttempt(), model)
                .inputHash(execution.getInputHash())
                .outputHash(execution.getOutputHash())
                .decision(execution.getStatus().name())
                .actor(AuditLog.ORCHESTRATOR)
                .detail(execution.getCommitSha() == null ? null : "commit=" + execution.getCommitSha())
                .at(clock.instant()));
        if (stage.approval() != null) {
            approvals.request(execution, stage.approval());
            if (approvals.autoApproves(stage.approval())) {
                approvals.approve(execution.getRunId(), stageId, ApprovalService.AUTO_ACTOR, "auto-approved (mock mode)");
                executions.put(stageId, stageExecutions.findById(execution.getId()).orElseThrow());
            }
        }
        markStaleDescendants(executions, executions.get(stageId));
        return true;
    }

    /** Downstream stages built from a different version of this stage's output must run again. */
    private void markStaleDescendants(Map<String, StageExecution> executions, StageExecution changed) {
        for (String descendantId : workflow.descendantsOf(changed.getStageId())) {
            StageExecution descendant = executions.get(descendantId);
            boolean hasOutput = descendant.getStatus() == StageStatus.SUCCEEDED
                    || descendant.getStatus() == StageStatus.AWAITING_APPROVAL;
            String consumed = descendant.consumedUpstream().get(changed.getStageId());
            if (!hasOutput || consumed == null || consumed.equals(changed.getOutputHash())) {
                continue;
            }
            String reason = "upstream stage " + changed.getStageId() + " changed (" + shortHash(consumed) + " -> "
                    + shortHash(changed.getOutputHash()) + ")";
            if (descendant.getStatus() == StageStatus.AWAITING_APPROVAL) {
                approvals.supersede(descendant, AuditLog.ORCHESTRATOR, reason);
            }
            String staleOutput = descendant.getOutputHash();
            descendant.markStale(reason);
            stageExecutions.save(descendant);
            audit.record(AuditEvent.of("STAGE_STALE", descendant.getRunId())
                    .stage(descendantId, descendant.getRevision())
                    .outputHash(staleOutput)
                    .decision(StageStatus.STALE.name())
                    .actor(AuditLog.ORCHESTRATOR)
                    .detail(reason)
                    .at(clock.instant()));
        }
    }

    private boolean failed(StageExecution execution, String error) {
        execution.fail(error, clock.instant());
        stageExecutions.save(execution);
        auditStage(execution, "STAGE_FAILED", StageStatus.FAILED.name(), error);
        return false;
    }

    private void block(StageExecution execution, String reason) {
        execution.block(reason);
        stageExecutions.save(execution);
        auditStage(execution, "STAGE_BLOCKED", StageStatus.BLOCKED.name(), reason);
    }

    private WorkflowRun safeStop(WorkflowRun run, Map<String, StageExecution> executions, String failedStageId) {
        for (StageExecution execution : executions.values()) {
            if (execution.getStatus() == StageStatus.PENDING || execution.getStatus() == StageStatus.STALE) {
                block(execution, "run safe-stopped after stage " + failedStageId + " failed");
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
        if (rolledBack.target() != null) {
            auditRun(run, "ROLLBACK", "reset " + run.getBranch() + " to " + rolledBack.target()
                    + "; failed state kept at " + rolledBack.preservedRef());
        }
        run.safeStop(reason, rolledBack.target(), rolledBack.preservedRef(), clock.instant());
        auditRun(run, "RUN_SAFE_STOPPED", reason);
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

    private void auditStage(StageExecution execution, String event, String decision, String detail) {
        audit.record(AuditEvent.of(event, execution.getRunId())
                .stage(execution.getStageId(), execution.getRevision())
                .inputHash(execution.getInputHash())
                .decision(decision)
                .actor(AuditLog.ORCHESTRATOR)
                .detail(detail)
                .at(clock.instant()));
    }

    private void auditRun(WorkflowRun run, String event, String detail) {
        audit.record(AuditEvent.of(event, run.getId())
                .decision(run.getStatus().name())
                .actor(AuditLog.ORCHESTRATOR)
                .detail(detail)
                .at(clock.instant()));
    }

    private static String shortHash(String hash) {
        return hash == null ? "none" : hash.substring(0, Math.min(8, hash.length()));
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

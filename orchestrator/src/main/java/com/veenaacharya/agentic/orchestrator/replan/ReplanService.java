package com.veenaacharya.agentic.orchestrator.replan;

import com.veenaacharya.agentic.orchestrator.audit.AuditEvent;
import com.veenaacharya.agentic.orchestrator.audit.AuditLog;
import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Re-plans a stage at a human's request: the stage runs again with the reason as feedback, and the
 * engine marks every downstream stage built from the old output STALE once the new output differs.
 * Nothing is approved on the way: re-run stages pass their gates and approvals again. The number of
 * revisions per stage is bounded so a disagreement cannot loop forever.
 */
@Component
@EnableConfigurationProperties(ReplanProperties.class)
public class ReplanService {

    private static final Set<StageStatus> REPLANNABLE =
            EnumSet.of(StageStatus.SUCCEEDED, StageStatus.AWAITING_APPROVAL, StageStatus.FAILED);

    private final WorkflowDefinition workflow;
    private final WorkflowEngine engine;
    private final ApprovalService approvals;
    private final WorkflowRunRepository runs;
    private final StageExecutionRepository stageExecutions;
    private final AuditLog audit;
    private final ReplanProperties properties;
    private final Clock clock;

    public ReplanService(WorkflowDefinition workflow, WorkflowEngine engine, ApprovalService approvals,
                         WorkflowRunRepository runs, StageExecutionRepository stageExecutions, AuditLog audit,
                         ReplanProperties properties, Clock clock) {
        this.workflow = workflow;
        this.engine = engine;
        this.approvals = approvals;
        this.runs = runs;
        this.stageExecutions = stageExecutions;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    public WorkflowRun replan(String runId, String stageId, String actor, String reason) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("re-planning needs an actor");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("re-planning needs a reason; it is given to the model as feedback");
        }
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL && run.getStatus() != RunStatus.FAILED) {
            throw new IllegalStateException("run " + runId + " cannot be re-planned while " + run.getStatus());
        }
        workflow.stage(stageId);
        StageExecution target = stage(runId, stageId);
        if (!REPLANNABLE.contains(target.getStatus())) {
            throw new IllegalStateException("stage " + stageId + " has no output to re-plan (" + target.getStatus() + ")");
        }
        if (target.getRevision() >= properties.maxRevisions()) {
            throw new IllegalStateException("stage " + stageId + " has reached " + target.getRevision()
                    + " revisions, the limit; start a new run with a clearer request");
        }

        String previousOutput = target.getOutputHash();
        approvals.supersede(target, actor, "re-planned: " + reason);
        target.replan("Re-plan requested by " + actor + ": " + reason);
        stageExecutions.save(target);
        for (String descendantId : workflow.descendantsOf(stageId)) {
            StageExecution descendant = stage(runId, descendantId);
            if (descendant.getStatus() == StageStatus.BLOCKED) {
                descendant.reopen();
                stageExecutions.save(descendant);
            }
        }
        run.reopen();
        runs.save(run);
        audit.record(AuditEvent.of("REPLAN_REQUESTED", runId)
                .stage(stageId, target.getRevision())
                .outputHash(previousOutput)
                .decision("REPLAN")
                .actor(actor)
                .detail(reason)
                .at(clock.instant()));
        return engine.resume(runId);
    }

    private StageExecution stage(String runId, String stageId) {
        return stageExecutions.findByRunIdOrderByIdAsc(runId).stream()
                .filter(s -> s.getStageId().equals(stageId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown stage " + stageId + " in run " + runId));
    }
}

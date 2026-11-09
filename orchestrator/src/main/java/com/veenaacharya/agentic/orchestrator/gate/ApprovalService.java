package com.veenaacharya.agentic.orchestrator.gate;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.audit.AuditEvent;
import com.veenaacharya.agentic.orchestrator.audit.AuditLog;
import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.ApprovalStatus;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Records approval requests and decisions, keeps the stage state in step with them, and audits each one. */
@Component
public class ApprovalService {

    public static final String KIND_RELEASE = "release";
    public static final String AUTO_ACTOR = "auto";

    private final ApprovalRepository approvals;
    private final StageExecutionRepository stageExecutions;
    private final AuditLog audit;
    private final Clock clock;
    private final boolean autoApprove;

    public ApprovalService(ApprovalRepository approvals,
                           StageExecutionRepository stageExecutions,
                           AuditLog audit,
                           Clock clock,
                           ApprovalProperties properties,
                           AgentProperties agent) {
        if (properties.autoApprove() && !"mock".equals(agent.mode())) {
            throw new IllegalStateException(
                    "orchestrator.approval.auto-approve is only allowed with orchestrator.agent.mode=mock");
        }
        this.approvals = approvals;
        this.stageExecutions = stageExecutions;
        this.audit = audit;
        this.clock = clock;
        this.autoApprove = properties.autoApprove();
    }

    public Approval request(StageExecution execution, String kind) {
        Approval approval = approvals.save(
                new Approval(execution.getRunId(), execution.getStageId(), kind, clock.instant()));
        audit.record(AuditEvent.of("APPROVAL_REQUESTED", execution.getRunId())
                .stage(execution.getStageId(), execution.getRevision())
                .outputHash(execution.getOutputHash())
                .decision(ApprovalStatus.PENDING.name())
                .actor(AuditLog.ORCHESTRATOR)
                .detail("kind=" + kind)
                .at(clock.instant()));
        return approval;
    }

    /** Release always needs a human, even with auto-approve on. */
    public boolean autoApproves(String kind) {
        return autoApprove && !KIND_RELEASE.equals(kind);
    }

    /** The latest approval for the stage; earlier revisions of a re-planned stage have their own. */
    public Optional<Approval> find(String runId, String stageId) {
        return approvals.findFirstByRunIdAndStageIdOrderByIdDesc(runId, stageId);
    }

    public List<Approval> forRun(String runId) {
        return approvals.findByRunIdOrderByIdAsc(runId);
    }

    public Approval approve(String runId, String stageId, String actor, String note) {
        Approval approval = pending(runId, stageId);
        StageExecution execution = awaitingStage(runId, stageId);
        approval.approve(actor, note, clock.instant());
        execution.approve(clock.instant());
        stageExecutions.save(execution);
        return decided(approval, execution, "APPROVAL_GRANTED");
    }

    public Approval reject(String runId, String stageId, String actor, String reason) {
        Approval approval = pending(runId, stageId);
        StageExecution execution = awaitingStage(runId, stageId);
        approval.reject(actor, reason, clock.instant());
        execution.reject("rejected by " + actor + (reason == null || reason.isBlank() ? "" : ": " + reason),
                clock.instant());
        stageExecutions.save(execution);
        return decided(approval, execution, "APPROVAL_REJECTED");
    }

    /** Closes a still-pending approval whose output is being replaced; returns empty if none was pending. */
    public Optional<Approval> supersede(StageExecution execution, String actor, String reason) {
        Optional<Approval> latest = find(execution.getRunId(), execution.getStageId())
                .filter(a -> a.getStatus() == ApprovalStatus.PENDING);
        latest.ifPresent(approval -> {
            approval.supersede(actor, reason, clock.instant());
            decided(approval, execution, "APPROVAL_SUPERSEDED");
        });
        return latest;
    }

    private Approval decided(Approval approval, StageExecution execution, String event) {
        Approval saved = approvals.save(approval);
        audit.record(AuditEvent.of(event, approval.getRunId())
                .stage(approval.getStageId(), execution.getRevision())
                .outputHash(execution.getOutputHash())
                .decision(approval.getStatus().name())
                .actor(approval.getDecidedBy())
                .detail("kind=" + approval.getKind()
                        + (approval.getReason() == null || approval.getReason().isBlank()
                        ? "" : "; reason=" + approval.getReason()))
                .at(clock.instant()));
        return saved;
    }

    private Approval pending(String runId, String stageId) {
        return find(runId, stageId).orElseThrow(() -> new IllegalArgumentException(
                "no approval requested for stage " + stageId + " in run " + runId));
    }

    private StageExecution awaitingStage(String runId, String stageId) {
        return stageExecutions.findByRunIdOrderByIdAsc(runId).stream()
                .filter(s -> s.getStageId().equals(stageId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown stage " + stageId + " in run " + runId));
    }
}

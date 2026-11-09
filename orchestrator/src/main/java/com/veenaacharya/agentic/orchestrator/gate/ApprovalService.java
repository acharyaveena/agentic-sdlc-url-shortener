package com.veenaacharya.agentic.orchestrator.gate;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Records approval requests and decisions, and keeps the stage state in step with them. */
@Component
public class ApprovalService {

    public static final String KIND_RELEASE = "release";
    public static final String AUTO_ACTOR = "auto";

    private final ApprovalRepository approvals;
    private final StageExecutionRepository stageExecutions;
    private final Clock clock;
    private final boolean autoApprove;

    public ApprovalService(ApprovalRepository approvals,
                           StageExecutionRepository stageExecutions,
                           Clock clock,
                           ApprovalProperties properties,
                           AgentProperties agent) {
        if (properties.autoApprove() && !"mock".equals(agent.mode())) {
            throw new IllegalStateException(
                    "orchestrator.approval.auto-approve is only allowed with orchestrator.agent.mode=mock");
        }
        this.approvals = approvals;
        this.stageExecutions = stageExecutions;
        this.clock = clock;
        this.autoApprove = properties.autoApprove();
    }

    public Approval request(StageExecution execution, String kind) {
        return approvals.save(new Approval(execution.getRunId(), execution.getStageId(), kind, clock.instant()));
    }

    /** Release always needs a human, even with auto-approve on. */
    public boolean autoApproves(String kind) {
        return autoApprove && !KIND_RELEASE.equals(kind);
    }

    public Optional<Approval> find(String runId, String stageId) {
        return approvals.findByRunIdAndStageId(runId, stageId);
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
        return approvals.save(approval);
    }

    public Approval reject(String runId, String stageId, String actor, String reason) {
        Approval approval = pending(runId, stageId);
        StageExecution execution = awaitingStage(runId, stageId);
        approval.reject(actor, reason, clock.instant());
        execution.reject("rejected by " + actor + (reason == null || reason.isBlank() ? "" : ": " + reason),
                clock.instant());
        stageExecutions.save(execution);
        return approvals.save(approval);
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

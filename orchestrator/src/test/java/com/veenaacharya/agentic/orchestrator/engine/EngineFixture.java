package com.veenaacharya.agentic.orchestrator.engine;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.audit.AuditLog;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalProperties;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.gate.StageGates;
import com.veenaacharya.agentic.orchestrator.recovery.RecoveryProperties;
import com.veenaacharya.agentic.orchestrator.recovery.RetryingStageRunner;
import com.veenaacharya.agentic.orchestrator.recovery.RollbackStrategy;
import com.veenaacharya.agentic.orchestrator.replan.ReplanProperties;
import com.veenaacharya.agentic.orchestrator.replan.ReplanService;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.DecisionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageAttemptRepository;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowLoader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/** Builds a fully wired engine for tests; every collaborator has a test-friendly default that can be swapped. */
public final class EngineFixture {

    public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final WorkflowRunRepository runs;
    private final StageExecutionRepository stages;
    private final StageAttemptRepository attempts;
    private final ApprovalRepository approvalRepository;
    private final DecisionRepository decisions;

    private WorkflowDefinition workflow = new WorkflowLoader().loadFromClasspath("workflow.yml");
    private StageExecutor executor = context -> StageResult.success("ok");
    private AgentProperties agent = new AgentProperties("mock", null, null, 0);
    private RecoveryProperties recovery = new RecoveryProperties(1, Map.of());
    private boolean autoApprove;
    private RunWorkspace workspace = RunWorkspace.NONE;
    private RollbackStrategy rollback = RollbackStrategy.NONE;
    private AuditLog audit = AuditLog.NONE;
    private int maxRevisions = 3;
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    public EngineFixture(WorkflowRunRepository runs, StageExecutionRepository stages, StageAttemptRepository attempts,
                         ApprovalRepository approvalRepository, DecisionRepository decisions) {
        this.runs = runs;
        this.stages = stages;
        this.attempts = attempts;
        this.approvalRepository = approvalRepository;
        this.decisions = decisions;
    }

    public EngineFixture workflow(WorkflowDefinition value) {
        workflow = value;
        return this;
    }

    public EngineFixture executor(StageExecutor value) {
        executor = value;
        return this;
    }

    public EngineFixture agent(AgentProperties value) {
        agent = value;
        return this;
    }

    public EngineFixture recovery(RecoveryProperties value) {
        recovery = value;
        return this;
    }

    public EngineFixture autoApprove(boolean value) {
        autoApprove = value;
        return this;
    }

    public EngineFixture workspace(RunWorkspace value) {
        workspace = value;
        return this;
    }

    public EngineFixture rollback(RollbackStrategy value) {
        rollback = value;
        return this;
    }

    public EngineFixture audit(AuditLog value) {
        audit = value;
        return this;
    }

    public EngineFixture maxRevisions(int value) {
        maxRevisions = value;
        return this;
    }

    public EngineFixture clock(Clock value) {
        clock = value;
        return this;
    }

    public ApprovalService approvals() {
        return new ApprovalService(approvalRepository, stages, audit, clock, new ApprovalProperties(autoApprove), agent);
    }

    public WorkflowEngine engine() {
        ApprovalService approvals = approvals();
        StageGates gates = new StageGates(workflow, approvals);
        RetryingStageRunner runner = new RetryingStageRunner(executor, gates, agent, recovery, clock);
        return new WorkflowEngine(workflow, runner, gates, approvals, workspace, rollback, runs, stages, attempts,
                decisions, audit, clock);
    }

    public ReplanService replanner(WorkflowEngine engine) {
        return new ReplanService(workflow, engine, approvals(), runs, stages, audit,
                new ReplanProperties(maxRevisions), clock);
    }
}

package com.veenaacharya.agentic.orchestrator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.agent.MockAgentRuntime;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalProperties;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.gate.StageGates;
import com.veenaacharya.agentic.orchestrator.recovery.RecoveryProperties;
import com.veenaacharya.agentic.orchestrator.recovery.RetryingStageRunner;
import com.veenaacharya.agentic.orchestrator.recovery.RollbackStrategy;
import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.ApprovalStatus;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageAttemptRepository;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowLoader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class WorkflowEngineApprovalTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final WorkflowDefinition WORKFLOW = new WorkflowLoader().loadFromClasspath("workflow.yml");
    private static final AgentProperties AGENT = new AgentProperties("mock", null, null, 0);

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private StageExecutionRepository stageExecutions;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private StageAttemptRepository attempts;

    private final MockAgentRuntime runtime = new MockAgentRuntime();

    @Test
    void runPausesAtRequirementsSignOffBeforeAnyDownstreamStageRuns() {
        WorkflowRun run = engine(false).run("greenfield", ChangeType.GREENFIELD, "shorten urls");

        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(statuses(run)).containsEntry("requirements", StageStatus.AWAITING_APPROVAL)
                .containsEntry("impact-analysis", StageStatus.PENDING)
                .containsEntry("design", StageStatus.PENDING);
        assertThat(runtime.requests()).extracting(r -> r.stageId()).containsExactly("requirements");
        Approval approval = approvalRepository.findByRunIdAndStageId(run.getId(), "requirements").orElseThrow();
        assertThat(approval.getKind()).isEqualTo("requirements-signoff");
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void fullGreenfieldRunNeedsThreeHumanDecisions() {
        WorkflowEngine engine = engine(false);
        ApprovalService approvals = approvals(false);
        WorkflowRun run = engine.run("greenfield", ChangeType.GREENFIELD, "shorten urls");

        approvals.approve(run.getId(), "requirements", "alice", "scope is right");
        run = engine.resume(run.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(statuses(run)).containsEntry("requirements", StageStatus.SUCCEEDED)
                .containsEntry("impact-analysis", StageStatus.SKIPPED)
                .containsEntry("design", StageStatus.AWAITING_APPROVAL)
                .containsEntry("code", StageStatus.PENDING);

        approvals.approve(run.getId(), "design", "alice", "");
        run = engine.resume(run.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(statuses(run)).containsEntry("code", StageStatus.SUCCEEDED)
                .containsEntry("tests", StageStatus.SUCCEEDED)
                .containsEntry("build-test", StageStatus.SUCCEEDED)
                .containsEntry("docs", StageStatus.SUCCEEDED)
                .containsEntry("policy-scan", StageStatus.SUCCEEDED)
                .containsEntry("release-readiness", StageStatus.AWAITING_APPROVAL);

        approvals.approve(run.getId(), "release-readiness", "bob", "ship it");
        run = engine.resume(run.getId());

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(approvalRepository.findByRunIdOrderByIdAsc(run.getId()))
                .extracting(Approval::getStageId, Approval::getKind, Approval::getStatus, Approval::getDecidedBy)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("requirements", "requirements-signoff",
                                ApprovalStatus.APPROVED, "alice"),
                        org.assertj.core.groups.Tuple.tuple("design", "design-and-schema",
                                ApprovalStatus.APPROVED, "alice"),
                        org.assertj.core.groups.Tuple.tuple("release-readiness", "release",
                                ApprovalStatus.APPROVED, "bob"));
    }

    @Test
    void downstreamStagesReceiveOnlyApprovedUpstreamOutput() {
        WorkflowEngine engine = engine(false);
        WorkflowRun run = engine.run("greenfield", ChangeType.GREENFIELD, "shorten urls");
        approvals(false).approve(run.getId(), "requirements", "alice", "");

        engine.resume(run.getId());

        String designPrompt = runtime.requests().stream()
                .filter(r -> r.stageId().equals("design")).findFirst().orElseThrow().userPrompt();
        assertThat(designPrompt).contains("## Output of stage: requirements")
                .contains("## Problem statement");
    }

    @Test
    void rejectedDesignFailsTheRunAndBlocksImplementation() {
        WorkflowEngine engine = engine(false);
        ApprovalService approvals = approvals(false);
        WorkflowRun run = engine.run("greenfield", ChangeType.GREENFIELD, "make analytics better");
        approvals.approve(run.getId(), "requirements", "alice", "");
        engine.resume(run.getId());

        approvals.reject(run.getId(), "design", "alice", "scope too broad");
        run = engine.resume(run.getId());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        StageExecution design = stage(run, "design");
        assertThat(design.getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(design.getError()).isEqualTo("rejected by alice: scope too broad");
        assertThat(statuses(run)).containsEntry("code", StageStatus.BLOCKED)
                .containsEntry("release-readiness", StageStatus.BLOCKED);
        assertThat(approvalRepository.findByRunIdAndStageId(run.getId(), "design").orElseThrow().getReason())
                .isEqualTo("scope too broad");
    }

    @Test
    void exitGateFailsStageWhoseOutputLacksRequiredSections() {
        runtime.respondWith("requirements", "just some text");

        WorkflowRun run = engine(false).run("greenfield", ChangeType.GREENFIELD, "x");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stage(run, "requirements").getError()).isEqualTo("exit gate failed: missing required sections: "
                + "[Problem statement, Ambiguities, Assumptions, Acceptance criteria]");
        assertThat(approvalRepository.findByRunIdOrderByIdAsc(run.getId())).isEmpty();
    }

    @Test
    void autoApproveSkipsHumanForRequirementsAndDesignButNeverForRelease() {
        WorkflowEngine engine = engine(true);

        WorkflowRun run = engine.run("greenfield", ChangeType.GREENFIELD, "shorten urls");

        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(statuses(run)).containsEntry("design", StageStatus.SUCCEEDED)
                .containsEntry("release-readiness", StageStatus.AWAITING_APPROVAL);
        assertThat(approvalRepository.findByRunIdOrderByIdAsc(run.getId()))
                .extracting(Approval::getStageId, Approval::getDecidedBy)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("requirements", "auto"),
                        org.assertj.core.groups.Tuple.tuple("design", "auto"),
                        org.assertj.core.groups.Tuple.tuple("release-readiness", null));
    }

    @Test
    void entryGateBlocksStageWhenUpstreamSignOffHasNoApprovalRecord() {
        WorkflowEngine engine = engine(false);
        WorkflowRun run = engine.run("greenfield", ChangeType.GREENFIELD, "x");
        // Simulates tampering: stage state says approved, but no approval record was ever decided.
        StageExecution requirements = stage(run, "requirements");
        requirements.approve(NOW);
        stageExecutions.save(requirements);

        run = engine.resume(run.getId());

        StageExecution design = stage(run, "design");
        assertThat(design.getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(design.getError())
                .isEqualTo("entry gate failed: no approved sign-off for upstream stage requirements");
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void finishedOrUnknownRunsCannotBeResumed() {
        WorkflowEngine engine = engine(false);
        WorkflowRun run = engine.run("greenfield", ChangeType.GREENFIELD, "x");
        ApprovalService approvals = approvals(false);
        approvals.reject(run.getId(), "requirements", "alice", "no");
        WorkflowRun finished = engine.resume(run.getId());

        assertThat(finished.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThatThrownBy(() -> engine.resume(run.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run " + run.getId() + " already finished: FAILED");
        assertThatThrownBy(() -> engine.resume("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown run: nope");
    }

    private ApprovalService approvals(boolean autoApprove) {
        return new ApprovalService(approvalRepository, stageExecutions, Clock.fixed(NOW, ZoneOffset.UTC),
                new ApprovalProperties(autoApprove), AGENT);
    }

    private WorkflowEngine engine(boolean autoApprove) {
        ApprovalService approvals = approvals(autoApprove);
        StageGates gates = new StageGates(WORKFLOW, approvals);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RetryingStageRunner runner = new RetryingStageRunner(executor(), gates, AGENT,
                new RecoveryProperties(1, Map.of()), clock);
        return new WorkflowEngine(WORKFLOW, runner, gates, approvals, RunWorkspace.NONE, RollbackStrategy.NONE,
                runs, stageExecutions, attempts, clock);
    }

    /** Model-backed stages use the mock agent; build and policy-scan stages are stood in for. */
    private StageExecutor executor() {
        AgentStageExecutor agent = new AgentStageExecutor(runtime, AGENT);
        return context -> switch (context.stage().kind()) {
            case "build" -> StageResult.success("## Build result\n\nExit code: 0\n");
            case "policy-scan" -> StageResult.success("## Findings\nnone\n\n## Verdict\nPASS\n");
            default -> agent.execute(context);
        };
    }

    private Map<String, StageStatus> statuses(WorkflowRun run) {
        Map<String, StageStatus> statuses = new LinkedHashMap<>();
        stageExecutions.findByRunIdOrderByIdAsc(run.getId()).forEach(s -> statuses.put(s.getStageId(), s.getStatus()));
        return statuses;
    }

    private StageExecution stage(WorkflowRun run, String stageId) {
        return stageExecutions.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(s -> s.getStageId().equals(stageId)).findFirst().orElseThrow();
    }
}

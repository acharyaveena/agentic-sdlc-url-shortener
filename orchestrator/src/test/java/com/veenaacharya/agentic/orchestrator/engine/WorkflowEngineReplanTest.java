package com.veenaacharya.agentic.orchestrator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.agent.AgentRequest;
import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.agent.MockAgentRuntime;
import com.veenaacharya.agentic.orchestrator.audit.AuditEvent;
import com.veenaacharya.agentic.orchestrator.audit.RecordingAuditLog;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.replan.ReplanService;
import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/** Decision lineage, audit trail, re-planning and stale detection, with the real workflow and mock agent. */
@DataJpaTest
class WorkflowEngineReplanTest {

    private static final AgentProperties AGENT = new AgentProperties("mock", null, null, 0);

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private StageExecutionRepository stageExecutions;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private StageAttemptRepository attempts;

    @Autowired
    private DecisionRepository decisions;

    private final MockAgentRuntime runtime = new MockAgentRuntime();
    private final RecordingAuditLog audit = new RecordingAuditLog();

    @Test
    void everyOutputIsRecordedWithTheHashesOfWhatItWasBuiltFrom() {
        WorkflowRun run = fixture(true).engine().run("demo", ChangeType.GREENFIELD, "shorten urls");

        Map<String, String> outputs = new java.util.HashMap<>();
        stageExecutions.findByRunIdOrderByIdAsc(run.getId()).forEach(s -> outputs.put(s.getStageId(), s.getOutputHash()));
        List<Decision> lineage = decisions.findByRunIdOrderByIdAsc(run.getId());

        assertThat(lineage).extracting(Decision::getStageId).containsExactlyInAnyOrder(
                "requirements", "design", "code", "tests", "build-test", "docs", "policy-scan", "release-readiness");
        assertThat(lineage).allSatisfy(d -> {
            assertThat(d.getOutputHash()).isEqualTo(outputs.get(d.getStageId())).hasSize(64);
            assertThat(d.getInputHash()).hasSize(64);
            assertThat(d.getRevision()).isEqualTo(1);
        });
        assertThat(decision(run, "requirements", 1).getUpstreamHashes()).isEmpty();
        assertThat(decision(run, "design", 1).getUpstreamHashes())
                .isEqualTo("requirements=" + outputs.get("requirements"));
        assertThat(decision(run, "code", 1).getUpstreamHashes())
                .isEqualTo("requirements=" + outputs.get("requirements") + ";design=" + outputs.get("design"));
    }

    @Test
    void auditTrailRecordsEveryStepWithRunStageAttemptModelHashesDecisionAndActor() {
        WorkflowRun run = fixture(true).engine().run("demo", ChangeType.GREENFIELD, "shorten urls");

        assertThat(audit.names()).startsWith("RUN_STARTED", "STAGE_STARTED", "ATTEMPT_SUCCEEDED",
                        "STAGE_AWAITING_APPROVAL", "APPROVAL_REQUESTED", "APPROVAL_GRANTED", "STAGE_SKIPPED")
                .endsWith("STAGE_AWAITING_APPROVAL", "APPROVAL_REQUESTED", "RUN_PAUSED");
        assertThat(audit.events()).allSatisfy(e -> {
            assertThat(e.runId()).isEqualTo(run.getId());
            assertThat(e.timestamp()).isEqualTo(EngineFixture.NOW.toString());
            assertThat(e.actor()).isNotBlank();
        });
        AuditEvent attempt = audit.named("ATTEMPT_SUCCEEDED").get(0);
        assertThat(attempt.stage()).isEqualTo("requirements");
        assertThat(attempt.revision()).isEqualTo(1);
        assertThat(attempt.attempt()).isEqualTo(1);
        assertThat(attempt.model()).isEqualTo("claude-sonnet-5.5");
        assertThat(attempt.actor()).isEqualTo("agent");
        assertThat(attempt.inputHash()).hasSize(64);
        AuditEvent output = audit.named("STAGE_AWAITING_APPROVAL").get(0);
        assertThat(output.outputHash()).isEqualTo(stage(run, "requirements").getOutputHash());
        assertThat(audit.named("APPROVAL_GRANTED").get(0).actor()).isEqualTo("auto");
        assertThat(audit.named("STAGE_SKIPPED").get(0).stage()).isEqualTo("impact-analysis");
    }

    @Test
    void rejectedDesignIsRedoneWithTheReasonAndMustBeApprovedAgain() {
        Fixture f = new Fixture(false);
        WorkflowRun run = f.engine.run("demo", ChangeType.GREENFIELD, "make analytics better");
        f.approvals.approve(run.getId(), "requirements", "alice", "");
        f.engine.resume(run.getId());
        String firstDesign = stage(run, "design").getOutputHash();

        f.approvals.reject(run.getId(), "design", "alice", "split into phases");
        run = f.replans.replan(run.getId(), "design", "alice", "split into phases");

        StageExecution design = stage(run, "design");
        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(design.getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(design.getRevision()).isEqualTo(2);
        assertThat(design.getOutputHash()).isNotEqualTo(firstDesign);
        assertThat(design.getOutput()).contains("Revision note: this version addresses: Re-plan requested by alice: "
                + "split into phases");
        assertThat(lastPrompt("design")).contains("## Previous attempt failed\nRe-plan requested by alice: split into phases");
        assertThat(approvalsFor(run, "design")).extracting(Approval::getStatus)
                .containsExactly(ApprovalStatus.REJECTED, ApprovalStatus.PENDING);
        assertThat(stage(run, "code").getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(attempts.findByRunIdOrderByIdAsc(run.getId())).filteredOn(a -> a.getStageId().equals("design"))
                .extracting(StageAttempt::getRevision).containsExactly(1, 2);

        f.approvals.approve(run.getId(), "design", "alice", "phased plan is fine");
        run = f.engine.resume(run.getId());

        assertThat(stage(run, "code").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(stage(run, "release-readiness").getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(decision(run, "code", 1).getUpstreamHashes()).contains("design=" + design.getOutputHash());
    }

    @Test
    void rejectedRunThatAlreadyFailedIsRevivedAndBlockedStagesReopen() {
        Fixture f = new Fixture(false);
        WorkflowRun run = f.engine.run("demo", ChangeType.GREENFIELD, "req");
        f.approvals.approve(run.getId(), "requirements", "alice", "");
        f.engine.resume(run.getId());
        f.approvals.reject(run.getId(), "design", "alice", "wrong");
        run = f.engine.resume(run.getId());
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(stage(run, "code").getStatus()).isEqualTo(StageStatus.BLOCKED);

        run = f.replans.replan(run.getId(), "design", "bob", "use a smaller scope");

        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(run.getFinishedAt()).isNull();
        assertThat(stage(run, "design").getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(stage(run, "code").getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(stage(run, "release-readiness").getStatus()).isEqualTo(StageStatus.PENDING);
    }

    @Test
    void changedUpstreamOutputMakesBuiltOnStagesStaleAndSupersedesTheirPendingApproval() {
        Fixture f = new Fixture(false);
        WorkflowRun run = f.engine.run("demo", ChangeType.GREENFIELD, "make analytics better");
        f.approvals.approve(run.getId(), "requirements", "alice", "");
        f.engine.resume(run.getId());
        String oldRequirements = stage(run, "requirements").getOutputHash();

        run = f.replans.replan(run.getId(), "requirements", "alice", "include data retention");

        StageExecution requirements = stage(run, "requirements");
        StageExecution design = stage(run, "design");
        assertThat(requirements.getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(requirements.getRevision()).isEqualTo(2);
        assertThat(design.getStatus()).isEqualTo(StageStatus.STALE);
        assertThat(design.getRevision()).isEqualTo(2);
        assertThat(design.getError()).isEqualTo("upstream stage requirements changed (" + oldRequirements.substring(0, 8)
                + " -> " + requirements.getOutputHash().substring(0, 8) + ")");
        assertThat(approvalsFor(run, "design")).extracting(Approval::getStatus).containsExactly(ApprovalStatus.SUPERSEDED);
        assertThat(audit.names()).containsSubsequence("REPLAN_REQUESTED", "STAGE_STARTED", "STAGE_AWAITING_APPROVAL",
                "APPROVAL_REQUESTED", "APPROVAL_SUPERSEDED", "STAGE_STALE", "RUN_PAUSED");
        assertThat(audit.named("STAGE_STALE").get(0).stage()).isEqualTo("design");

        f.approvals.approve(run.getId(), "requirements", "alice", "retention included");
        run = f.engine.resume(run.getId());

        design = stage(run, "design");
        assertThat(design.getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(approvalsFor(run, "design")).extracting(Approval::getStatus)
                .containsExactly(ApprovalStatus.SUPERSEDED, ApprovalStatus.PENDING);
        assertThat(decision(run, "design", 2).getUpstreamHashes())
                .isEqualTo("requirements=" + requirements.getOutputHash());
    }

    @Test
    void identicalUpstreamOutputLeavesDownstreamStagesAlone() {
        String fixedRequirements = new MockAgentRuntime()
                .complete(new AgentRequest("r", "requirements", "m", "s", "no feedback"));
        runtime.respondWith("requirements", fixedRequirements);
        Fixture f = new Fixture(false);
        WorkflowRun run = f.engine.run("demo", ChangeType.GREENFIELD, "req");
        f.approvals.approve(run.getId(), "requirements", "alice", "");
        f.engine.resume(run.getId());

        run = f.replans.replan(run.getId(), "requirements", "alice", "double-check");

        assertThat(stage(run, "requirements").getRevision()).isEqualTo(2);
        assertThat(stage(run, "design").getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(stage(run, "design").getRevision()).isEqualTo(1);
        assertThat(approvalsFor(run, "design")).extracting(Approval::getStatus).containsExactly(ApprovalStatus.PENDING);
        assertThat(audit.names()).doesNotContain("STAGE_STALE");
    }

    @Test
    void revisionsPerStageAreBounded() {
        Fixture f = new Fixture(false, 2);
        WorkflowRun run = f.engine.run("demo", ChangeType.GREENFIELD, "req");

        f.replans.replan(run.getId(), "requirements", "alice", "first change");

        assertThatThrownBy(() -> f.replans.replan(run.getId(), "requirements", "alice", "second change"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stage requirements has reached 2 revisions, the limit; start a new run with a clearer request");
    }

    @Test
    void replanIsRefusedWithoutAReasonForStagesWithoutOutputOrForFinishedRuns() {
        Fixture f = new Fixture(false);
        WorkflowRun paused = f.engine.run("demo", ChangeType.GREENFIELD, "req");

        assertThatThrownBy(() -> f.replans.replan(paused.getId(), "code", "alice", "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stage code has no output to re-plan (PENDING)");
        assertThatThrownBy(() -> f.replans.replan(paused.getId(), "requirements", "alice", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("re-planning needs a reason; it is given to the model as feedback");
        assertThatThrownBy(() -> f.replans.replan(paused.getId(), "deploy", "alice", "x"))
                .hasMessage("unknown stage: deploy");
        assertThatThrownBy(() -> f.replans.replan("nope", "design", "alice", "x"))
                .hasMessage("unknown run: nope");

        WorkflowRun stopped = f.engine.run("demo", ChangeType.GREENFIELD, "req", "requirements:always");
        assertThatThrownBy(() -> f.replans.replan(stopped.getId(), "requirements", "alice", "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run " + stopped.getId() + " cannot be re-planned while SAFE_STOPPED");
    }

    private final class Fixture {

        final WorkflowEngine engine;
        final ApprovalService approvals;
        final ReplanService replans;

        Fixture(boolean autoApprove) {
            this(autoApprove, 3);
        }

        Fixture(boolean autoApprove, int maxRevisions) {
            EngineFixture fixture = fixture(autoApprove).maxRevisions(maxRevisions);
            engine = fixture.engine();
            approvals = fixture.approvals();
            replans = fixture.replanner(engine);
        }
    }

    private EngineFixture fixture(boolean autoApprove) {
        AgentStageExecutor agent = new AgentStageExecutor(runtime, new AgentProperties("mock", null, null, 0));
        StageExecutor executor = context -> switch (context.stage().kind()) {
            case "build" -> StageResult.success("## Build result\n\nExit code: 0\n");
            case "policy-scan" -> StageResult.success("## Findings\nnone\n\n## Verdict\nPASS\n");
            default -> agent.execute(context);
        };
        return new EngineFixture(runs, stageExecutions, attempts, approvalRepository, decisions)
                .executor(executor).agent(AGENT).autoApprove(autoApprove).audit(audit);
    }

    private String lastPrompt(String stageId) {
        return runtime.requests().stream().filter(r -> r.stageId().equals(stageId))
                .reduce((a, b) -> b).orElseThrow().userPrompt();
    }

    private List<Approval> approvalsFor(WorkflowRun run, String stageId) {
        return approvalRepository.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(a -> a.getStageId().equals(stageId)).toList();
    }

    private Decision decision(WorkflowRun run, String stageId, int revision) {
        return decisions.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(d -> d.getStageId().equals(stageId) && d.getRevision() == revision)
                .findFirst().orElseThrow();
    }

    private StageExecution stage(WorkflowRun run, String stageId) {
        return stageExecutions.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(s -> s.getStageId().equals(stageId)).findFirst().orElseThrow();
    }
}

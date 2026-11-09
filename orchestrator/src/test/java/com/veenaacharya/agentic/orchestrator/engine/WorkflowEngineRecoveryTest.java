package com.veenaacharya.agentic.orchestrator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.agent.MockAgentRuntime;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.recovery.RecoveryProperties;
import com.veenaacharya.agentic.orchestrator.recovery.RollbackResult;
import com.veenaacharya.agentic.orchestrator.recovery.RollbackStrategy;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
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
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowLoader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class WorkflowEngineRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final WorkflowDefinition WORKFLOW = new WorkflowLoader().loadFromClasspath("workflow.yml");
    private static final AgentProperties AGENT =
            new AgentProperties("mock", "claude-sonnet-5.5", Map.of("design", "claude-opus-5.5"), 0);
    private static final RecoveryProperties RECOVERY =
            new RecoveryProperties(2, Map.of("claude-opus-5.5", "claude-sonnet-5.5"));

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
    private final RecordingWorkspace workspace = new RecordingWorkspace();
    private final RecordingRollback rollback = new RecordingRollback();

    @Test
    void retriedStageRecordsEveryAttemptAndTheRunCarriesOn() {
        WorkflowRun run = engine(true).run("demo", ChangeType.GREENFIELD, "req", "design:2");

        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(run.getChaos()).isEqualTo("design:2");
        assertThat(stage(run, "design").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(stage(run, "design").getAttempt()).isEqualTo(3);
        assertThat(attemptsOf(run, "design"))
                .extracting(StageAttempt::getAttemptNumber, StageAttempt::getModel, StageAttempt::isSucceeded,
                        StageAttempt::getError)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "claude-opus-5.5", false,
                                "chaos: injected failure on attempt 1"),
                        org.assertj.core.groups.Tuple.tuple(2, "claude-opus-5.5", false,
                                "chaos: injected failure on attempt 2"),
                        org.assertj.core.groups.Tuple.tuple(3, "claude-sonnet-5.5", true, null));
        assertThat(stage(run, "requirements").getAttempt()).isEqualTo(1);
        assertThat(rollback.calls).isEmpty();
    }

    @Test
    void permanentFailureRollsBackToTheLastApprovedCheckpointAndSafeStops() {
        WorkflowRun run = engine(true).run("demo", ChangeType.GREENFIELD, "req", "build-test:always");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(run.getStopReason())
                .isEqualTo("stage build-test failed after 1 attempt(s): chaos: injected failure on attempt 1");
        assertThat(rollback.calls).containsExactly("sha-design");
        assertThat(run.getRollbackTarget()).isEqualTo("sha-design");
        assertThat(run.getPreservedRef()).isEqualTo("agent-failed/test");
        assertThat(run.getFinishedAt()).isEqualTo(NOW);
        assertThat(stage(run, "code").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(stage(run, "build-test").getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(stage(run, "docs").getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(stage(run, "release-readiness").getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
    }

    @Test
    void failureBeforeAnySignOffRollsBackToWhereTheRunStarted() {
        WorkflowRun run = engine(true).run("demo", ChangeType.GREENFIELD, "req", "requirements:always");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(rollback.calls).containsExactly("base-sha");
        assertThat(attemptsOf(run, "requirements")).hasSize(2);
        assertThat(run.getStopReason()).startsWith("stage requirements failed after 2 attempt(s): ");
        assertThat(stage(run, "impact-analysis").getError()).isEqualTo("upstream stage did not succeed: requirements");
        assertThat(stage(run, "design").getError()).isEqualTo("upstream stage did not succeed: impact-analysis");
    }

    @Test
    void noNewStageStartsAfterAFailureButARunningSiblingFinishes() {
        WorkflowDefinition workflow = new WorkflowDefinition("w", List.of(
                new StageDefinition("a", List.of(), Set.of()),
                new StageDefinition("fails", List.of("a"), Set.of()),
                new StageDefinition("slow", List.of("a"), Set.of()),
                new StageDefinition("after-slow", List.of("slow"), Set.of())));
        List<String> executed = new CopyOnWriteArrayList<>();
        StageExecutor executor = ctx -> {
            executed.add(ctx.stage().id());
            if (ctx.stage().id().equals("slow")) {
                sleep(500);
            }
            return ctx.stage().id().equals("fails") ? StageResult.failure("nope") : StageResult.success("ok");
        };

        WorkflowRun run = engine(workflow, executor, false, new RecoveryProperties(1, Map.of()))
                .run("w", ChangeType.GREENFIELD, "req");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stage(run, "slow").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(stage(run, "after-slow").getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(stage(run, "after-slow").getError()).isEqualTo("run safe-stopped after stage fails failed");
        assertThat(executed).doesNotContain("after-slow");
    }

    @Test
    void rollbackThatFailsIsReportedAndTheRunStillStops() {
        rollback.failWith = "HEAD is on main";

        WorkflowRun run = engine(true).run("demo", ChangeType.GREENFIELD, "req", "code:always");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(run.getStopReason()).endsWith("; rollback failed, workspace left as is: HEAD is on main");
        assertThat(run.getRollbackTarget()).isNull();
        assertThat(run.getPreservedRef()).isNull();
    }

    @Test
    void rejectedApprovalFailsTheRunWithoutRollback() {
        WorkflowEngine engine = engine(false);
        WorkflowRun run = engine.run("demo", ChangeType.GREENFIELD, "req");
        approvals(false).reject(run.getId(), "requirements", "alice", "wrong scope");

        run = engine.resume(run.getId());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getStopReason()).isNull();
        assertThat(rollback.calls).isEmpty();
    }

    @Test
    void chaosIsKeptOnTheRunAndStillAppliesAfterResume() {
        WorkflowEngine engine = engine(false);
        WorkflowRun run = engine.run("demo", ChangeType.GREENFIELD, "req", " design:1 ");
        approvals(false).approve(run.getId(), "requirements", "alice", "");

        run = engine.resume(run.getId());

        assertThat(run.getChaos()).isEqualTo("design:1");
        assertThat(attemptsOf(run, "design")).extracting(StageAttempt::isSucceeded).containsExactly(false, true);
        assertThat(stage(run, "design").getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);
    }

    @Test
    void unknownChaosStageIsRejectedBeforeAnythingIsPersisted() {
        long before = runs.count();

        assertThatThrownBy(() -> engine(true).run("demo", ChangeType.GREENFIELD, "req", "deploy:1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chaos names unknown stage: deploy");
        assertThat(runs.count()).isEqualTo(before);
        assertThat(workspace.started).isZero();
    }

    @Test
    void safeStoppedRunCannotBeResumed() {
        WorkflowEngine engine = engine(true);
        WorkflowRun run = engine.run("demo", ChangeType.GREENFIELD, "req", "requirements:always");

        assertThatThrownBy(() -> engine.resume(run.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run " + run.getId() + " already finished: SAFE_STOPPED");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ApprovalService approvals(boolean autoApprove) {
        return fixture(WORKFLOW, executor(), autoApprove, RECOVERY).approvals();
    }

    private WorkflowEngine engine(boolean autoApprove) {
        return engine(WORKFLOW, executor(), autoApprove, RECOVERY);
    }

    private WorkflowEngine engine(WorkflowDefinition workflow, StageExecutor executor, boolean autoApprove,
                                  RecoveryProperties recovery) {
        return fixture(workflow, executor, autoApprove, recovery).engine();
    }

    private EngineFixture fixture(WorkflowDefinition workflow, StageExecutor executor, boolean autoApprove,
                                  RecoveryProperties recovery) {
        return new EngineFixture(runs, stageExecutions, attempts, approvalRepository, decisions)
                .workflow(workflow).executor(executor).agent(AGENT).recovery(recovery).autoApprove(autoApprove)
                .workspace(workspace).rollback(rollback);
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

    private List<StageAttempt> attemptsOf(WorkflowRun run, String stageId) {
        return attempts.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(a -> a.getStageId().equals(stageId)).toList();
    }

    private StageExecution stage(WorkflowRun run, String stageId) {
        return stageExecutions.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(s -> s.getStageId().equals(stageId)).findFirst().orElseThrow();
    }

    private static final class RecordingWorkspace implements RunWorkspace {

        int started;

        @Override
        public Start startRun(WorkflowRun run) {
            started++;
            return new Start("agent/test", "base-sha");
        }

        @Override
        public void resumeRun(WorkflowRun run) {
        }

        @Override
        public String commitStage(String runId, String stageId, String output, List<String> files) {
            return "sha-" + stageId;
        }
    }

    private static final class RecordingRollback implements RollbackStrategy {

        final List<String> calls = new ArrayList<>();
        String failWith;

        @Override
        public RollbackResult rollback(WorkflowRun run, String targetCommit) {
            calls.add(targetCommit);
            if (failWith != null) {
                throw new IllegalStateException(failWith);
            }
            return new RollbackResult(targetCommit, "agent-failed/test");
        }
    }
}

package com.veenaacharya.agentic.orchestrator.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageAttempt;
import com.veenaacharya.agentic.orchestrator.state.StageAttemptRepository;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowLoader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.DefaultApplicationArguments;

class OrchestratorCliTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final WorkflowEngine engine = mock(WorkflowEngine.class);
    private final AgentStageExecutor executor = mock(AgentStageExecutor.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final StageExecutionRepository stageExecutions = mock(StageExecutionRepository.class);
    private final StageAttemptRepository attempts = mock(StageAttemptRepository.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final OrchestratorCli cli = new OrchestratorCli(engine, executor,
            new WorkflowLoader().loadFromClasspath("workflow.yml"), runs, stageExecutions, attempts, approvals,
            new PrintStream(buffer, true, StandardCharsets.UTF_8));

    @Test
    void printsUsageWithoutArguments() {
        cli.run(new DefaultApplicationArguments());

        assertThat(output()).isEqualTo(OrchestratorCli.USAGE + System.lineSeparator());
        verifyNoInteractions(engine);
    }

    @Test
    void previewRunsSingleStageWithoutUpstreamInputOrEngine() {
        when(executor.execute(any())).thenReturn(StageResult.success("# reqs"));

        cli.run(new DefaultApplicationArguments("--stage=requirements", "--request=shorten urls"));

        ArgumentCaptor<StageContext> captured = ArgumentCaptor.forClass(StageContext.class);
        verify(executor).execute(captured.capture());
        assertThat(captured.getValue().stage().id()).isEqualTo("requirements");
        assertThat(captured.getValue().request()).isEqualTo("shorten urls");
        assertThat(captured.getValue().changeType()).isEqualTo(ChangeType.GREENFIELD);
        assertThat(captured.getValue().upstreamOutputs()).isEmpty();
        assertThat(output()).isEqualTo("# reqs" + System.lineSeparator());
        verifyNoInteractions(engine);
    }

    @Test
    void previewReportsFailureAndRejectsUnknownStage() {
        when(executor.execute(any())).thenReturn(StageResult.failure("timed out"));

        cli.run(new DefaultApplicationArguments("--stage=design"));

        assertThat(output()).isEqualTo("Stage failed: timed out" + System.lineSeparator());
        assertThatThrownBy(() -> cli.run(new DefaultApplicationArguments("--stage=deploy")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown stage: deploy");
    }

    @Test
    void startsRunAndPrintsStageStatuses() {
        WorkflowRun run = new WorkflowRun("greenfield", ChangeType.GREENFIELD, "build it", NOW);
        run.finish(RunStatus.SUCCEEDED, NOW);
        when(engine.run("greenfield", ChangeType.GREENFIELD, "build it", "")).thenReturn(run);
        StageExecution skipped = new StageExecution(run.getId(), "impact-analysis");
        skipped.skip(NOW);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of(skipped));

        cli.run(new DefaultApplicationArguments(
                "--scenario=greenfield", "--change-type=greenfield", "--request=build it"));

        assertThat(output())
                .contains("Run " + run.getId() + " (greenfield, greenfield): SUCCEEDED")
                .contains("impact-analysis    SKIPPED");
    }

    @Test
    void pausedRunTellsReviewerHowToApproveOrReject() {
        WorkflowRun run = new WorkflowRun("expiry", ChangeType.BROWNFIELD, "", NOW);
        run.markAwaitingApproval();
        when(engine.run("expiry", ChangeType.BROWNFIELD, "", "")).thenReturn(run);
        StageExecution waiting = new StageExecution(run.getId(), "requirements");
        waiting.start(NOW);
        waiting.awaitApproval(null);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of(waiting));
        when(approvals.forRun(run.getId()))
                .thenReturn(List.of(new Approval(run.getId(), "requirements", "requirements-signoff", NOW)));

        cli.run(new DefaultApplicationArguments("--scenario=expiry"));

        assertThat(output())
                .contains("requirements       AWAITING_APPROVAL")
                .contains("Awaiting approval: requirements (requirements-signoff)")
                .contains("--approve=" + run.getId() + ":requirements --actor=<you>")
                .contains("--reject=" + run.getId() + ":requirements --reason=<why> --actor=<you>");
    }

    @Test
    void failedStagesShowTheirError() {
        WorkflowRun run = new WorkflowRun("x", ChangeType.BROWNFIELD, "", NOW);
        run.finish(RunStatus.FAILED, NOW);
        when(engine.run("x", ChangeType.BROWNFIELD, "", "")).thenReturn(run);
        StageExecution failed = new StageExecution(run.getId(), "code");
        failed.start(NOW);
        failed.fail("exit gate failed: missing required sections: [X]", NOW);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of(failed));

        cli.run(new DefaultApplicationArguments("--scenario=x"));

        assertThat(output()).contains("  code: exit gate failed: missing required sections: [X]");
    }

    @Test
    void chaosFlagIsAnnouncedAndPassedToTheEngine() {
        WorkflowRun run = new WorkflowRun("demo", ChangeType.GREENFIELD, "", NOW);
        when(engine.run("demo", ChangeType.GREENFIELD, "", "design:2")).thenReturn(run);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of());

        cli.run(new DefaultApplicationArguments("--scenario=demo", "--change-type=greenfield", "--chaos=design:2"));

        verify(engine).run("demo", ChangeType.GREENFIELD, "", "design:2");
        assertThat(output()).startsWith("CHAOS MODE: injecting failures (design:2) to demonstrate recovery");
    }

    @Test
    void stagesWithSeveralAttemptsListEachAttemptWithModelAndOutcome() {
        WorkflowRun run = new WorkflowRun("demo", ChangeType.GREENFIELD, "", NOW);
        when(engine.run("demo", ChangeType.GREENFIELD, "", "")).thenReturn(run);
        StageExecution design = new StageExecution(run.getId(), "design");
        design.start(NOW);
        design.recordAttempts(3);
        design.succeed("ok", NOW);
        StageExecution code = new StageExecution(run.getId(), "code");
        code.start(NOW);
        code.succeed("ok", NOW);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of(design, code));
        when(attempts.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of(
                new StageAttempt(run.getId(), "design", 1, "claude-opus-5.5", false,
                        "chaos: injected failure on attempt 1\nmore detail", NOW, NOW),
                new StageAttempt(run.getId(), "design", 2, "claude-opus-5.5", false, "timed out", NOW, NOW),
                new StageAttempt(run.getId(), "design", 3, "claude-sonnet-5.5", true, null, NOW, NOW),
                new StageAttempt(run.getId(), "code", 1, "claude-sonnet-5.5", true, null, NOW, NOW)));

        cli.run(new DefaultApplicationArguments("--scenario=demo", "--change-type=greenfield"));

        assertThat(output())
                .contains("design             SUCCEEDED (3 attempts)")
                .contains("attempt 1 claude-opus-5.5    failed: chaos: injected failure on attempt 1" + System.lineSeparator())
                .contains("attempt 2 claude-opus-5.5    failed: timed out")
                .contains("attempt 3 claude-sonnet-5.5  ok")
                .contains("code               SUCCEEDED" + System.lineSeparator())
                .doesNotContain("more detail");
    }

    @Test
    void safeStoppedRunShowsReasonRollbackTargetAndPreservedTag() {
        WorkflowRun run = new WorkflowRun("demo", ChangeType.GREENFIELD, "", NOW);
        run.assignBranch("agent/demo-12345678");
        run.safeStop("stage build-test failed after 1 attempt(s): chaos", "0123456789abcdef0123",
                "agent-failed/12345678", NOW);
        when(engine.run("demo", ChangeType.GREENFIELD, "", "build-test:always")).thenReturn(run);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of());

        cli.run(new DefaultApplicationArguments("--scenario=demo", "--change-type=greenfield",
                "--chaos=build-test:always"));

        assertThat(output())
                .contains(": SAFE_STOPPED")
                .contains("Safe-stopped: stage build-test failed after 1 attempt(s): chaos")
                .contains("Rolled back agent/demo-12345678 to 0123456789; the failed state is kept at tag "
                        + "agent-failed/12345678");
    }

    @Test
    void approveRecordsDecisionThenResumesRun() {
        WorkflowRun run = new WorkflowRun("s", ChangeType.GREENFIELD, "", NOW);
        run.finish(RunStatus.SUCCEEDED, NOW);
        when(approvals.approve("run-1", "design", "alice", "fine"))
                .thenReturn(new Approval("run-1", "design", "design-and-schema", NOW));
        when(engine.resume("run-1")).thenReturn(run);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of());

        cli.run(new DefaultApplicationArguments("--approve=run-1:design", "--actor=alice", "--reason=fine"));

        InOrder order = inOrder(approvals, engine);
        order.verify(approvals).approve("run-1", "design", "alice", "fine");
        order.verify(engine).resume("run-1");
        assertThat(output()).contains("Approved design (design-and-schema) as alice");
    }

    @Test
    void approveDefaultsActorToReviewer() {
        WorkflowRun run = new WorkflowRun("s", ChangeType.GREENFIELD, "", NOW);
        when(approvals.approve("run-1", "design", "reviewer", ""))
                .thenReturn(new Approval("run-1", "design", "design-and-schema", NOW));
        when(engine.resume("run-1")).thenReturn(run);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of());

        cli.run(new DefaultApplicationArguments("--approve=run-1:design"));

        verify(approvals).approve("run-1", "design", "reviewer", "");
    }

    @Test
    void rejectNeedsAReason() {
        assertThatThrownBy(() -> cli.run(new DefaultApplicationArguments("--reject=run-1:design")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--reject needs --reason=<text>");
        verifyNoInteractions(approvals, engine);
    }

    @Test
    void rejectRecordsDecisionThenResumesRun() {
        WorkflowRun run = new WorkflowRun("s", ChangeType.GREENFIELD, "", NOW);
        run.finish(RunStatus.FAILED, NOW);
        when(approvals.reject("run-1", "design", "bob", "too broad"))
                .thenReturn(new Approval("run-1", "design", "design-and-schema", NOW));
        when(engine.resume("run-1")).thenReturn(run);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of());

        cli.run(new DefaultApplicationArguments("--reject=run-1:design", "--reason=too broad", "--actor=bob"));

        verify(approvals).reject("run-1", "design", "bob", "too broad");
        verify(engine).resume("run-1");
        assertThat(output()).contains("Rejected design (design-and-schema) as bob").contains(": FAILED");
    }

    @Test
    void malformedApproveTargetIsRejected() {
        assertThatThrownBy(() -> cli.run(new DefaultApplicationArguments("--approve=just-a-run")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected <run-id>:<stage-id>, was: just-a-run");
        verifyNoInteractions(approvals, engine);
    }

    @Test
    void resumeContinuesRun() {
        WorkflowRun run = new WorkflowRun("s", ChangeType.GREENFIELD, "", NOW);
        when(engine.resume("run-1")).thenReturn(run);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of());

        cli.run(new DefaultApplicationArguments("--resume=run-1"));

        verify(engine).resume("run-1");
        assertThat(output()).contains("(s, greenfield): PENDING");
    }

    @Test
    void statusShowsRunWithoutTouchingEngine() {
        WorkflowRun run = new WorkflowRun("s", ChangeType.BROWNFIELD, "", NOW);
        when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of());

        cli.run(new DefaultApplicationArguments("--status=run-1"));

        assertThat(output()).contains("(s, brownfield): PENDING");
        verifyNoInteractions(engine);
        assertThatThrownBy(() -> cli.run(new DefaultApplicationArguments("--status=nope")))
                .hasMessage("unknown run: nope");
    }

    @Test
    void rejectsUnknownChangeType() {
        assertThatThrownBy(() -> cli.run(new DefaultApplicationArguments(
                "--scenario=x", "--change-type=bluefield")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--change-type must be greenfield or brownfield, was: bluefield");
        verifyNoInteractions(engine);
    }

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }
}

package com.veenaacharya.agentic.orchestrator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalProperties;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.gate.StageGates;
import com.veenaacharya.agentic.orchestrator.recovery.RecoveryProperties;
import com.veenaacharya.agentic.orchestrator.recovery.RetryingStageRunner;
import com.veenaacharya.agentic.orchestrator.recovery.RollbackStrategy;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class WorkflowEngineTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final WorkflowDefinition WORKFLOW = new WorkflowLoader().loadFromClasspath("workflow-plain.yml");

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private StageExecutionRepository stageExecutions;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private StageAttemptRepository attempts;

    @Test
    void greenfieldRunSkipsImpactAnalysisAndPersistsEveryStage() {
        WorkflowRun run = engine(ctx -> StageResult.success("out-" + ctx.stage().id()))
                .run("greenfield", ChangeType.GREENFIELD, "build a url shortener");

        assertThat(runs.findById(run.getId())).get()
                .extracting(WorkflowRun::getStatus, WorkflowRun::getFinishedAt)
                .containsExactly(RunStatus.SUCCEEDED, NOW);
        assertThat(statuses(run)).containsExactly(
                Map.entry("requirements", StageStatus.SUCCEEDED),
                Map.entry("impact-analysis", StageStatus.SKIPPED),
                Map.entry("design", StageStatus.SUCCEEDED),
                Map.entry("code", StageStatus.SUCCEEDED),
                Map.entry("tests", StageStatus.SUCCEEDED),
                Map.entry("build-test", StageStatus.SUCCEEDED),
                Map.entry("docs", StageStatus.SUCCEEDED),
                Map.entry("policy-scan", StageStatus.SUCCEEDED),
                Map.entry("release-readiness", StageStatus.SUCCEEDED));
        StageExecution design = stage(run, "design");
        assertThat(design.getOutput()).isEqualTo("out-design");
        assertThat(design.getStartedAt()).isEqualTo(NOW);
    }

    @Test
    void brownfieldRunExecutesImpactAnalysis() {
        WorkflowRun run = engine(ctx -> StageResult.success("ok"))
                .run("brownfield", ChangeType.BROWNFIELD, "add link expiry");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stage(run, "impact-analysis").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
    }

    @Test
    void independentStagesRunInParallelAndJoinWaitsForBoth() {
        CyclicBarrier codeAndTests = new CyclicBarrier(2);
        CyclicBarrier docsAndPolicy = new CyclicBarrier(2);
        List<String> events = new CopyOnWriteArrayList<>();

        StageExecutor executor = ctx -> {
            String id = ctx.stage().id();
            events.add("start:" + id);
            try {
                // Each barrier only trips if both siblings are running at the same time.
                if (id.equals("code") || id.equals("tests")) {
                    codeAndTests.await(5, TimeUnit.SECONDS);
                }
                if (id.equals("docs") || id.equals("policy-scan")) {
                    docsAndPolicy.await(5, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                throw new IllegalStateException("not run in parallel: " + id, e);
            }
            events.add("end:" + id);
            return StageResult.success("ok");
        };

        WorkflowRun run = engine(executor).run("parallel", ChangeType.GREENFIELD, "req");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        int buildStart = events.indexOf("start:build-test");
        assertThat(events.indexOf("end:code")).isLessThan(buildStart);
        assertThat(events.indexOf("end:tests")).isLessThan(buildStart);
        int releaseStart = events.indexOf("start:release-readiness");
        assertThat(events.indexOf("end:docs")).isLessThan(releaseStart);
        assertThat(events.indexOf("end:policy-scan")).isLessThan(releaseStart);
    }

    @Test
    void failedStageBlocksDownstreamButLetsRunningSiblingFinish() {
        StageExecutor executor = mock(StageExecutor.class);
        when(executor.execute(any())).thenAnswer(invocation -> {
            StageContext ctx = invocation.getArgument(0);
            return ctx.stage().id().equals("code")
                    ? StageResult.failure("compilation failed")
                    : StageResult.success("ok");
        });

        WorkflowRun run = engine(executor).run("failing", ChangeType.GREENFIELD, "req");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stage(run, "code").getError()).isEqualTo("compilation failed");
        assertThat(stage(run, "tests").getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(stage(run, "build-test").getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(stage(run, "build-test").getError()).isEqualTo("upstream stage did not succeed: code");
        assertThat(stage(run, "docs").getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(stage(run, "policy-scan").getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(stage(run, "release-readiness").getStatus()).isEqualTo(StageStatus.BLOCKED);
        verify(executor, times(1)).execute(argThat(ctx -> ctx.stage().id().equals("tests")));
        verify(executor, never()).execute(argThat(ctx -> ctx.stage().id().equals("build-test")));
    }

    @Test
    void exceptionFromExecutorFailsStageInsteadOfCrashingRun() {
        StageExecutor executor = mock(StageExecutor.class);
        when(executor.execute(any())).thenThrow(new IllegalStateException("model timeout"));

        WorkflowRun run = engine(executor).run("crash", ChangeType.BROWNFIELD, "req");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stage(run, "requirements").getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(stage(run, "requirements").getError()).isEqualTo("IllegalStateException: model timeout");
        assertThat(stage(run, "impact-analysis").getStatus()).isEqualTo(StageStatus.BLOCKED);
        assertThat(stage(run, "release-readiness").getStatus()).isEqualTo(StageStatus.BLOCKED);
        verify(executor, times(1)).execute(any());
    }

    @Test
    void nullResultIsTreatedAsFailure() {
        WorkflowRun run = engine(ctx -> null).run("null", ChangeType.GREENFIELD, "req");

        assertThat(stage(run, "requirements").getError()).isEqualTo("executor returned no result");
    }

    @Test
    void eachStageReceivesOutputsOfAllSucceededAncestors() {
        Map<String, StageContext> contexts = new ConcurrentHashMap<>();
        StageExecutor executor = ctx -> {
            contexts.put(ctx.stage().id(), ctx);
            return StageResult.success("out-" + ctx.stage().id());
        };

        WorkflowRun run = engine(executor).run("context", ChangeType.BROWNFIELD, "add expiry");

        assertThat(contexts.get("requirements").upstreamOutputs()).isEmpty();
        assertThat(contexts.get("design").upstreamOutputs()).containsExactly(
                Map.entry("requirements", "out-requirements"),
                Map.entry("impact-analysis", "out-impact-analysis"));
        assertThat(contexts.get("build-test").upstreamOutputs().keySet())
                .containsExactly("requirements", "impact-analysis", "design", "code", "tests");
        assertThat(contexts.get("docs").upstreamOutputs()).doesNotContainKey("policy-scan");
        StageContext design = contexts.get("design");
        assertThat(design.runId()).isEqualTo(run.getId());
        assertThat(design.request()).isEqualTo("add expiry");
        assertThat(design.changeType()).isEqualTo(ChangeType.BROWNFIELD);
    }

    @Test
    void skippedStageContributesNoOutput() {
        Map<String, StageContext> contexts = new ConcurrentHashMap<>();
        engine(ctx -> {
            contexts.put(ctx.stage().id(), ctx);
            return StageResult.success("out");
        }).run("greenfield", ChangeType.GREENFIELD, "req");

        assertThat(contexts).doesNotContainKey("impact-analysis");
        assertThat(contexts.get("design").upstreamOutputs().keySet()).containsExactly("requirements");
    }

    @Test
    void runGetsItsOwnBranchAndEachExecutedStageIsCommittedOnceWithItsFiles() {
        RecordingWorkspace workspace = new RecordingWorkspace();

        WorkflowRun run = engine(ctx -> StageResult.success("out-" + ctx.stage().id(),
                List.of("url-shortener/" + ctx.stage().id())), workspace).run("greenfield", ChangeType.GREENFIELD, "req");

        assertThat(run.getBranch()).isEqualTo("agent/test-branch");
        assertThat(run.getBaseCommit()).isEqualTo("base-sha");
        assertThat(runs.findById(run.getId()).orElseThrow().getBranch()).isEqualTo("agent/test-branch");
        assertThat(stage(run, "code").getCommitSha()).isEqualTo("sha-code");
        assertThat(stage(run, "impact-analysis").getCommitSha()).isNull();
        assertThat(workspace.started).isEqualTo(1);
        assertThat(workspace.commits).extracting(c -> c.stage())
                .containsExactlyInAnyOrder("requirements", "design", "code", "tests", "build-test", "docs",
                        "policy-scan", "release-readiness");
        assertThat(workspace.commits).allSatisfy(c -> {
            assertThat(c.runId()).isEqualTo(run.getId());
            assertThat(c.output()).isEqualTo("out-" + c.stage());
            assertThat(c.files()).containsExactly("url-shortener/" + c.stage());
        });
    }

    @Test
    void failedStageIsNotCommitted() {
        RecordingWorkspace workspace = new RecordingWorkspace();

        engine(ctx -> ctx.stage().id().equals("code") ? StageResult.failure("x") : StageResult.success("ok"),
                workspace).run("failing", ChangeType.GREENFIELD, "req");

        assertThat(workspace.commits).extracting(c -> c.stage()).containsExactlyInAnyOrder(
                "requirements", "design", "tests");
    }

    @Test
    void failedCommitFailsTheStageAndBlocksDownstream() {
        RecordingWorkspace workspace = new RecordingWorkspace();
        workspace.failCommitFor = "design";

        WorkflowRun run = engine(ctx -> StageResult.success("ok"), workspace)
                .run("commit-fail", ChangeType.GREENFIELD, "req");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(stage(run, "design").getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(stage(run, "design").getError()).isEqualTo("commit failed: git is unhappy");
        assertThat(stage(run, "code").getStatus()).isEqualTo(StageStatus.BLOCKED);
    }

    @Test
    void dirtyWorkspaceRefusesTheRunBeforeAnythingIsPersisted() {
        RecordingWorkspace workspace = new RecordingWorkspace();
        workspace.refuseStart = true;
        long runsBefore = runs.count();
        long stagesBefore = stageExecutions.count();

        assertThatThrownBy(() -> engine(ctx -> StageResult.success("ok"), workspace)
                .run("dirty", ChangeType.GREENFIELD, "req"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("working tree is dirty");

        assertThat(runs.count()).isEqualTo(runsBefore);
        assertThat(stageExecutions.count()).isEqualTo(stagesBefore);
    }

    private record Commit(String runId, String stage, String output, List<String> files) {
    }

    private static final class RecordingWorkspace implements RunWorkspace {

        final List<Commit> commits = new CopyOnWriteArrayList<>();
        int started;
        boolean refuseStart;
        String failCommitFor;

        @Override
        public Start startRun(WorkflowRun run) {
            if (refuseStart) {
                throw new IllegalStateException("working tree is dirty");
            }
            started++;
            return new Start("agent/test-branch", "base-sha");
        }

        @Override
        public void resumeRun(WorkflowRun run) {
        }

        @Override
        public String commitStage(String runId, String stageId, String output, List<String> files) {
            if (stageId.equals(failCommitFor)) {
                throw new IllegalStateException("git is unhappy");
            }
            commits.add(new Commit(runId, stageId, output, files));
            return "sha-" + stageId;
        }
    }

    private WorkflowEngine engine(StageExecutor executor) {
        return engine(executor, RunWorkspace.NONE);
    }

    private WorkflowEngine engine(StageExecutor executor, RunWorkspace workspace) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentProperties agent = new AgentProperties("mock", null, null, 0);
        ApprovalService approvals = new ApprovalService(approvalRepository, stageExecutions, clock,
                new ApprovalProperties(false), agent);
        StageGates gates = new StageGates(WORKFLOW, approvals);
        // One attempt and no fallback, so these tests see each stage's first result unchanged.
        RetryingStageRunner runner = new RetryingStageRunner(executor, gates, agent,
                new RecoveryProperties(1, Map.of()), clock);
        return new WorkflowEngine(WORKFLOW, runner, gates, approvals, workspace, RollbackStrategy.NONE,
                runs, stageExecutions, attempts, clock);
    }

    private Map<String, StageStatus> statuses(WorkflowRun run) {
        Map<String, StageStatus> statuses = new LinkedHashMap<>();
        stageExecutions.findByRunIdOrderByIdAsc(run.getId())
                .forEach(s -> statuses.put(s.getStageId(), s.getStatus()));
        return statuses;
    }

    private StageExecution stage(WorkflowRun run, String stageId) {
        return stageExecutions.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(s -> s.getStageId().equals(stageId))
                .findFirst()
                .orElseThrow();
    }
}

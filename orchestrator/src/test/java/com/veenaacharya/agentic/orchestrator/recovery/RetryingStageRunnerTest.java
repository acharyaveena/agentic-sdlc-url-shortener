package com.veenaacharya.agentic.orchestrator.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.gate.StageGates;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowLoader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RetryingStageRunnerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final AgentProperties AGENT = new AgentProperties("mock", "sonnet", Map.of("design", "opus"), 0);
    private static final RecoveryProperties RECOVERY = new RecoveryProperties(2, Map.of("opus", "sonnet"));

    private final StageGates gates = new StageGates(new WorkflowLoader().loadFromClasspath("workflow.yml"),
            Mockito.mock(ApprovalService.class));
    private final List<StageContext> calls = new CopyOnWriteArrayList<>();

    private RetryingStageRunner runner(StageExecutor executor, RecoveryProperties recovery) {
        StageExecutor recording = context -> {
            calls.add(context);
            return executor.execute(context);
        };
        return new RetryingStageRunner(recording, gates, AGENT, recovery, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StageContext context(String id, String kind, List<String> requiredSections) {
        return new StageContext("run-1", new StageDefinition(id, List.of(), Set.of(), requiredSections, null, kind),
                ChangeType.GREENFIELD, "req", Map.of());
    }

    private static StageContext design() {
        return context("design", "agent", List.of());
    }

    @Test
    void successOnFirstAttemptUsesTheStageModelOnce() {
        StageOutcome outcome = runner(ctx -> StageResult.success("ok"), RECOVERY).run(design(), ChaosPlan.NONE);

        assertThat(outcome.result()).isEqualTo(StageResult.success("ok"));
        assertThat(outcome.attempts()).containsExactly(new AttemptRecord(1, "opus", true, null, NOW, NOW));
        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.model()).isEqualTo("opus");
            assertThat(c.retryFeedback()).isNull();
        });
    }

    @Test
    void retriesWithTheFailureReasonThenFallsBackToTheFallbackModel() {
        StageExecutor executor = ctx -> ctx.model().equals("sonnet")
                ? StageResult.success("recovered")
                : StageResult.failure("bad answer " + calls.size());

        StageOutcome outcome = runner(executor, RECOVERY).run(design(), ChaosPlan.NONE);

        assertThat(outcome.result().output()).isEqualTo("recovered");
        assertThat(outcome.attempts()).extracting(AttemptRecord::number, AttemptRecord::model, AttemptRecord::succeeded)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "opus", false),
                        org.assertj.core.groups.Tuple.tuple(2, "opus", false),
                        org.assertj.core.groups.Tuple.tuple(3, "sonnet", true));
        assertThat(calls).extracting(StageContext::retryFeedback)
                .containsExactly(null, "bad answer 1", "bad answer 2");
    }

    @Test
    void whenEveryAttemptFailsTheLastErrorIsTheResult() {
        StageOutcome outcome = runner(ctx -> StageResult.failure("no " + ctx.model()), RECOVERY)
                .run(design(), ChaosPlan.NONE);

        assertThat(outcome.result()).isEqualTo(StageResult.failure("no sonnet"));
        assertThat(outcome.attempts()).hasSize(3).noneMatch(AttemptRecord::succeeded);
    }

    @Test
    void stageWithoutAFallbackModelGetsOnlyItsOwnAttempts() {
        RetryingStageRunner runner = runner(ctx -> StageResult.failure("x"), RECOVERY);

        StageOutcome outcome = runner.run(context("code", "files", List.of()), ChaosPlan.NONE);

        assertThat(outcome.attempts()).extracting(AttemptRecord::model).containsExactly("sonnet", "sonnet");
        assertThat(runner.modelsFor(new StageDefinition("code", List.of(), Set.of(), null, null, "files")))
                .containsExactly("sonnet", "sonnet");
    }

    @Test
    void buildAndPolicyScanStagesRunOnceWithoutAModel() {
        RetryingStageRunner runner = runner(ctx -> StageResult.failure("build failed"), RECOVERY);

        StageOutcome build = runner.run(context("build-test", "build", List.of()), ChaosPlan.NONE);
        StageOutcome scan = runner.run(context("policy-scan", "policy-scan", List.of()), ChaosPlan.NONE);

        assertThat(build.attempts()).containsExactly(new AttemptRecord(1, null, false, "build failed", NOW, NOW));
        assertThat(scan.attempts()).hasSize(1);
        assertThat(calls).extracting(StageContext::model).containsExactly(null, null);
    }

    @Test
    void exitGateFailureIsRetriedWithTheMissingSectionsAsFeedback() {
        StageExecutor executor = ctx -> StageResult.success(ctx.retryFeedback() == null ? "no headings" : "## Verdict\nok");

        StageOutcome outcome = runner(executor, RECOVERY)
                .run(context("design", "agent", List.of("Verdict")), ChaosPlan.NONE);

        assertThat(outcome.result().succeeded()).isTrue();
        assertThat(outcome.attempts()).hasSize(2);
        assertThat(outcome.attempts().get(0).error()).isEqualTo("exit gate failed: missing required sections: [Verdict]");
        assertThat(calls.get(1).retryFeedback()).isEqualTo("exit gate failed: missing required sections: [Verdict]");
    }

    @Test
    void exceptionsAndMissingResultsBecomeFailedAttempts() {
        StageOutcome thrown = runner(ctx -> {
            throw new IllegalStateException("boom");
        }, new RecoveryProperties(1, Map.of())).run(design(), ChaosPlan.NONE);
        StageOutcome empty = runner(ctx -> null, new RecoveryProperties(1, Map.of())).run(design(), ChaosPlan.NONE);

        assertThat(thrown.result().error()).isEqualTo("IllegalStateException: boom");
        assertThat(empty.result().error()).isEqualTo("executor returned no result");
    }

    @Test
    void chaosFailsAttemptsBeforeTheExecutorIsCalled() {
        StageOutcome outcome = runner(ctx -> StageResult.success("ok"), RECOVERY)
                .run(design(), ChaosPlan.parse("design:2"));

        assertThat(outcome.result().succeeded()).isTrue();
        assertThat(outcome.attempts()).extracting(AttemptRecord::error).containsExactly(
                "chaos: injected failure on attempt 1", "chaos: injected failure on attempt 2", null);
        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.model()).isEqualTo("sonnet");
            assertThat(c.retryFeedback()).isEqualTo("chaos: injected failure on attempt 2");
        });
    }

    @Test
    void chaosAlwaysOnABuildStageFailsItsOnlyAttempt() {
        StageOutcome outcome = runner(ctx -> StageResult.success("ok"), RECOVERY)
                .run(context("build-test", "build", List.of()), ChaosPlan.parse("build-test:always"));

        assertThat(outcome.result()).isEqualTo(StageResult.failure("chaos: injected failure on attempt 1"));
        assertThat(calls).isEmpty();
    }
}

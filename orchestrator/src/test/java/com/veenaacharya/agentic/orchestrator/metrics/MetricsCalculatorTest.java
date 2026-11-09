package com.veenaacharya.agentic.orchestrator.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageAttempt;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private static WorkflowRun run(RunStatus status, int durationSeconds) {
        WorkflowRun run = new WorkflowRun("s", ChangeType.GREENFIELD, "r", T0);
        switch (status) {
            case SAFE_STOPPED -> run.safeStop("x", "target", "tag", at(durationSeconds));
            case AWAITING_APPROVAL -> run.markAwaitingApproval();
            default -> run.finish(status, at(durationSeconds));
        }
        return run;
    }

    private static StageAttempt attempt(String runId, String stage, int revision, int number, String model,
                                        boolean ok, int start, int end) {
        return new StageAttempt(runId, stage, revision, number, model, ok, ok ? null : "err", at(start), at(end));
    }

    @Test
    void computesRunOutcomesRetriesFallbacksMttrRollbacksAndLatency() {
        WorkflowRun ok = run(RunStatus.SUCCEEDED, 100);
        WorkflowRun stopped = run(RunStatus.SAFE_STOPPED, 40);
        WorkflowRun rejected = run(RunStatus.FAILED, 60);
        WorkflowRun paused = run(RunStatus.AWAITING_APPROVAL, 0);
        List<StageAttempt> attempts = List.of(
                // design: two failures on opus then success on the fallback model; recovered 8 s after first failure
                attempt(ok.getId(), "design", 1, 1, "opus", false, 0, 2),
                attempt(ok.getId(), "design", 1, 2, "opus", false, 2, 6),
                attempt(ok.getId(), "design", 1, 3, "sonnet", true, 6, 10),
                attempt(ok.getId(), "code", 1, 1, "sonnet", true, 10, 14),
                attempt(stopped.getId(), "build-test", 1, 1, null, false, 0, 1),
                // a re-planned stage counts as its own stage run
                attempt(ok.getId(), "requirements", 1, 1, "opus", true, 0, 4),
                attempt(ok.getId(), "requirements", 2, 1, "opus", true, 20, 26));
        StageExecution replanned = new StageExecution(ok.getId(), "requirements");
        replanned.start(T0);
        replanned.succeed("v1", T0);
        replanned.replan("feedback");
        Approval approved = new Approval(ok.getId(), "design", "design-and-schema", at(10));
        approved.approve("alice", "", at(70));
        Approval rejection = new Approval(rejected.getId(), "design", "design-and-schema", at(0));
        rejection.reject("bob", "no", at(30));
        Approval pending = new Approval(paused.getId(), "requirements", "requirements-signoff", at(0));

        MetricsReport report = MetricsCalculator.compute("all runs", List.of(ok, stopped, rejected, paused),
                List.of(replanned, new StageExecution(ok.getId(), "code")), attempts,
                List.of(approved, rejection, pending));

        assertThat(report.runs()).isEqualTo(4);
        assertThat(report.succeeded()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.safeStopped()).isEqualTo(1);
        assertThat(report.inProgress()).isEqualTo(1);
        assertThat(report.successRate()).isEqualTo(1.0 / 3);
        assertThat(report.stageRuns()).isEqualTo(5);
        assertThat(report.retriedStageRuns()).isEqualTo(1);
        assertThat(report.retryRate()).isEqualTo(0.2);
        assertThat(report.retries()).isEqualTo(2);
        assertThat(report.fallbackAttempts()).isEqualTo(1);
        assertThat(report.recoveries()).isEqualTo(1);
        assertThat(report.mttr()).isEqualTo(Duration.ofSeconds(8));
        assertThat(report.rollbacks()).isEqualTo(1);
        assertThat(report.rollbackRate()).isEqualTo(1.0 / 3);
        assertThat(report.averageEndToEnd()).isEqualTo(Duration.ofSeconds(200).dividedBy(3));
        assertThat(report.maxEndToEnd()).isEqualTo(Duration.ofSeconds(100));
        assertThat(report.averageStageLatency()).containsEntry("design", Duration.ofSeconds(10))
                .containsEntry("requirements", Duration.ofSeconds(5))
                .containsEntry("build-test", Duration.ofSeconds(1));
        assertThat(report.approvalsDecided()).isEqualTo(2);
        assertThat(report.rejections()).isEqualTo(1);
        assertThat(report.averageApprovalWait()).isEqualTo(Duration.ofSeconds(45));
        assertThat(report.stageReruns()).isEqualTo(1);
    }

    @Test
    void emptyInputGivesZerosAndNoRates() {
        MetricsReport report = MetricsCalculator.compute("run x", List.of(), List.of(), List.of(), List.of());

        assertThat(report.successRate()).isNull();
        assertThat(report.retryRate()).isNull();
        assertThat(report.rollbackRate()).isNull();
        assertThat(report.mttr()).isNull();
        assertThat(report.averageEndToEnd()).isNull();
        assertThat(report.toMarkdown()).contains("| Success rate | n/a (0 of 0 finished runs) |")
                .contains("| MTTR | n/a over 0 recoveries |")
                .doesNotContain("Stage latency");
    }

    @Test
    void markdownShowsEveryMetricAndStageLatencySortedByStage() {
        MetricsReport report = new MetricsReport("run abc", 1, 1, 0, 0, 0, 4, 1, 2, 1, 1, Duration.ofMillis(1500),
                0, Duration.ofSeconds(125), Duration.ofSeconds(125),
                Map.of("tests", Duration.ofSeconds(3), "code", Duration.ofSeconds(2)),
                2, 0, Duration.ofHours(2).plusMinutes(5), 1);

        String md = report.toMarkdown();

        assertThat(md).startsWith("## Metrics (run abc)\n")
                .contains("| Runs | 1 (succeeded 1, failed 0, safe-stopped 0, in progress 0) |")
                .contains("| Success rate | 100% (1 of 1 finished runs) |")
                .contains("| Retry rate | 25% of stage runs (1 of 4) |")
                .contains("| Retries | 2 attempts beyond the first, 1 on a fallback model |")
                .contains("| Rollback rate | 0% (0 of 1 finished runs) |")
                .contains("| MTTR | 1.5 s over 1 recovery |")
                .contains("| End-to-end latency | avg 2m 05s, max 2m 05s (includes time waiting for approvals) |")
                .contains("| Approval wait | avg 2h 05m over 2 decisions (0 rejected) |")
                .contains("| Re-runs (re-plan or stale) | 1 |");
        assertThat(md.indexOf("| code |")).isLessThan(md.indexOf("| tests |"));
    }
}

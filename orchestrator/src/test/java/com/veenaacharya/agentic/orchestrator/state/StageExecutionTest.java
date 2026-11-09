package com.veenaacharya.agentic.orchestrator.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StageExecutionTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T10:00:05Z");

    @Test
    void newExecutionIsPendingFirstAttempt() {
        StageExecution execution = new StageExecution("run-1", "design");

        assertThat(execution.getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(execution.getAttempt()).isEqualTo(1);
        assertThat(execution.getRunId()).isEqualTo("run-1");
    }

    @Test
    void successRecordsOutputAndTimes() {
        StageExecution execution = new StageExecution("run-1", "design");
        execution.start(T0);
        execution.succeed("plan", T1);

        assertThat(execution.getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(execution.getOutput()).isEqualTo("plan");
        assertThat(execution.getStartedAt()).isEqualTo(T0);
        assertThat(execution.getFinishedAt()).isEqualTo(T1);
        assertThat(execution.isSatisfied()).isTrue();
        assertThat(execution.isDeadEnd()).isFalse();
    }

    @Test
    void failureTruncatesLongErrors() {
        StageExecution execution = new StageExecution("run-1", "code");
        execution.start(T0);
        execution.fail("x".repeat(5000), T1);

        assertThat(execution.getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(execution.getError()).hasSize(StageExecution.MAX_ERROR_LENGTH);
        assertThat(execution.isDeadEnd()).isTrue();
    }

    @Test
    void skippedIsSatisfiedAndBlockedIsDeadEnd() {
        StageExecution skipped = new StageExecution("run-1", "impact-analysis");
        skipped.skip(T0);
        StageExecution blocked = new StageExecution("run-1", "docs");
        blocked.block("upstream failed");

        assertThat(skipped.isSatisfied()).isTrue();
        assertThat(blocked.isDeadEnd()).isTrue();
        assertThat(blocked.getError()).isEqualTo("upstream failed");
    }

    @Test
    void illegalTransitionsAreRejected() {
        StageExecution execution = new StageExecution("run-1", "code");

        assertThatThrownBy(() -> execution.succeed("out", T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stage code must be RUNNING but is PENDING");

        execution.start(T0);
        assertThatThrownBy(() -> execution.skip(T1))
                .hasMessage("stage code must be PENDING but is RUNNING");
    }

    @Test
    void workflowRunStartsPendingAndRecordsFinish() {
        WorkflowRun run = new WorkflowRun("greenfield", ChangeType.GREENFIELD, "build it", T0);

        assertThat(run.getId()).hasSize(36);
        assertThat(run.getStatus()).isEqualTo(RunStatus.PENDING);

        run.markRunning();
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);

        run.finish(RunStatus.SUCCEEDED, T1);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getFinishedAt()).isEqualTo(T1);
    }
}

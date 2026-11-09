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
                .hasMessage("stage code must be PENDING or STALE but is RUNNING");
    }

    @Test
    void awaitingApprovalKeepsOutputButIsNotSatisfiedUntilApproved() {
        StageExecution execution = new StageExecution("run-1", "design");
        execution.start(T0);
        execution.awaitApproval("plan");

        assertThat(execution.getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(execution.getOutput()).isEqualTo("plan");
        assertThat(execution.getFinishedAt()).isNull();
        assertThat(execution.isSatisfied()).isFalse();
        assertThat(execution.isDeadEnd()).isFalse();

        execution.approve(T1);
        assertThat(execution.getStatus()).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(execution.getFinishedAt()).isEqualTo(T1);
        assertThat(execution.isSatisfied()).isTrue();
    }

    @Test
    void rejectedStageFailsWithReason() {
        StageExecution execution = new StageExecution("run-1", "design");
        execution.start(T0);
        execution.awaitApproval("plan");
        execution.reject("rejected by alice: too broad", T1);

        assertThat(execution.getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(execution.getError()).isEqualTo("rejected by alice: too broad");
        assertThat(execution.isDeadEnd()).isTrue();
    }

    @Test
    void approvalTransitionsRequireAwaitingState() {
        StageExecution execution = new StageExecution("run-1", "design");

        assertThatThrownBy(() -> execution.approve(T0))
                .hasMessage("stage design must be AWAITING_APPROVAL but is PENDING");
        assertThatThrownBy(() -> execution.reject("x", T0))
                .hasMessage("stage design must be AWAITING_APPROVAL but is PENDING");
        assertThatThrownBy(() -> execution.awaitApproval("out"))
                .hasMessage("stage design must be RUNNING but is PENDING");
    }

    @Test
    void approvalCanBeDecidedOnlyOnce() {
        Approval approval = new Approval("run-1", "design", "design-and-schema", T0);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.PENDING);

        approval.approve("alice", "ok", T1);

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getDecidedBy()).isEqualTo("alice");
        assertThat(approval.getRequestedAt()).isEqualTo(T0);
        assertThat(approval.getDecidedAt()).isEqualTo(T1);
        assertThatThrownBy(() -> approval.reject("bob", "no", T1))
                .hasMessage("approval for stage design is already APPROVED");
    }

    @Test
    void recordsAttemptCountAndCommit() {
        StageExecution execution = new StageExecution("run-1", "design");

        execution.recordAttempts(3);
        execution.recordCommit("abc123");

        assertThat(execution.getAttempt()).isEqualTo(3);
        assertThat(execution.getCommitSha()).isEqualTo("abc123");
        assertThatThrownBy(() -> execution.recordAttempts(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attempt count must be at least 1, was 0");
    }

    @Test
    void safeStopRecordsReasonRollbackAndFinishesTheRun() {
        WorkflowRun run = new WorkflowRun("s", ChangeType.GREENFIELD, "r", T0);
        run.assignBaseCommit("base");
        run.useChaos("design:2");
        assertThat(run.isFinished()).isFalse();

        run.safeStop("x".repeat(3000), "target", "agent-failed/1", T1);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(run.isFinished()).isTrue();
        assertThat(run.getStopReason()).hasSize(2000);
        assertThat(run.getRollbackTarget()).isEqualTo("target");
        assertThat(run.getPreservedRef()).isEqualTo("agent-failed/1");
        assertThat(run.getFinishedAt()).isEqualTo(T1);
        assertThat(run.getBaseCommit()).isEqualTo("base");
        assertThat(run.getChaos()).isEqualTo("design:2");
    }

    @Test
    void blankChaosIsStoredAsNoneAndOnlyTerminalStatusesAreFinished() {
        WorkflowRun run = new WorkflowRun("s", ChangeType.GREENFIELD, "r", T0);
        run.useChaos(" ");
        run.markAwaitingApproval();

        assertThat(run.getChaos()).isNull();
        assertThat(run.isFinished()).isFalse();
        run.finish(RunStatus.FAILED, T1);
        assertThat(run.isFinished()).isTrue();
    }

    @Test
    void attemptKeepsItsDetailsAndTruncatesLongErrors() {
        StageAttempt attempt = new StageAttempt("run-1", "design", 2, "claude-opus-5.5", false,
                "e".repeat(5000), T0, T1);

        assertThat(attempt.getRunId()).isEqualTo("run-1");
        assertThat(attempt.getStageId()).isEqualTo("design");
        assertThat(attempt.getAttemptNumber()).isEqualTo(2);
        assertThat(attempt.getModel()).isEqualTo("claude-opus-5.5");
        assertThat(attempt.isSucceeded()).isFalse();
        assertThat(attempt.getError()).hasSize(StageAttempt.MAX_ERROR_LENGTH);
        assertThat(attempt.getStartedAt()).isEqualTo(T0);
        assertThat(attempt.getFinishedAt()).isEqualTo(T1);
    }

    @Test
    void outputHashFollowsTheOutputAndInputsRoundTrip() {
        StageExecution execution = new StageExecution("run-1", "design");
        execution.recordInputs("in", java.util.Map.of("requirements", "abc"));
        execution.start(T0);
        execution.succeed("abc", T1);

        assertThat(execution.getOutputHash())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(execution.getInputHash()).isEqualTo("in");
        assertThat(execution.consumedUpstream()).containsExactly(java.util.Map.entry("requirements", "abc"));
        assertThat(new StageExecution("run-1", "x").consumedUpstream()).isEmpty();
    }

    @Test
    void staleStageGetsANewRevisionAndCanRunAgain() {
        StageExecution execution = new StageExecution("run-1", "design");
        execution.start(T0);
        execution.awaitApproval("v1");

        execution.markStale("upstream stage requirements changed");

        assertThat(execution.getStatus()).isEqualTo(StageStatus.STALE);
        assertThat(execution.getRevision()).isEqualTo(2);
        assertThat(execution.getError()).isEqualTo("upstream stage requirements changed");
        assertThat(execution.isSatisfied()).isFalse();
        assertThat(execution.isDeadEnd()).isFalse();

        execution.start(T1);
        assertThat(execution.getStatus()).isEqualTo(StageStatus.RUNNING);
        assertThat(execution.getError()).isNull();
    }

    @Test
    void replanReopensASucceededOrRejectedStageWithFeedback() {
        StageExecution execution = new StageExecution("run-1", "design");
        execution.start(T0);
        execution.awaitApproval("v1");
        execution.reject("rejected by alice: too broad", T1);

        execution.replan("Re-plan requested by alice: too broad");

        assertThat(execution.getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(execution.getRevision()).isEqualTo(2);
        assertThat(execution.getReplanFeedback()).isEqualTo("Re-plan requested by alice: too broad");
        assertThat(execution.getError()).isNull();
        assertThat(execution.getStartedAt()).isNull();
        assertThat(execution.getFinishedAt()).isNull();
    }

    @Test
    void staleClearsReplanFeedbackAndOnlyBlockedStagesReopen() {
        StageExecution replanned = new StageExecution("run-1", "design");
        replanned.start(T0);
        replanned.succeed("v1", T1);
        replanned.replan("feedback");
        replanned.start(T0);
        replanned.succeed("v2", T1);
        replanned.markStale("upstream changed");
        assertThat(replanned.getReplanFeedback()).isNull();
        assertThat(replanned.getRevision()).isEqualTo(3);

        StageExecution blocked = new StageExecution("run-1", "code");
        blocked.block("upstream stage did not succeed: design");
        blocked.reopen();
        assertThat(blocked.getStatus()).isEqualTo(StageStatus.PENDING);
        assertThat(blocked.getError()).isNull();

        assertThatThrownBy(() -> new StageExecution("run-1", "x").reopen())
                .hasMessage("stage x must be BLOCKED but is PENDING");
        assertThatThrownBy(() -> new StageExecution("run-1", "x").markStale("r"))
                .hasMessage("stage x must be SUCCEEDED or AWAITING_APPROVAL but is PENDING");
        assertThatThrownBy(() -> new StageExecution("run-1", "x").replan("r"))
                .hasMessage("stage x must be SUCCEEDED or AWAITING_APPROVAL or FAILED but is PENDING");
    }

    @Test
    void runReopensOnlyFromAwaitingApprovalOrFailed() {
        WorkflowRun paused = new WorkflowRun("s", ChangeType.GREENFIELD, "r", T0);
        paused.markAwaitingApproval();
        paused.reopen();
        assertThat(paused.getStatus()).isEqualTo(RunStatus.RUNNING);

        WorkflowRun failed = new WorkflowRun("s", ChangeType.GREENFIELD, "r", T0);
        failed.finish(RunStatus.FAILED, T1);
        failed.reopen();
        assertThat(failed.getFinishedAt()).isNull();

        WorkflowRun stopped = new WorkflowRun("s", ChangeType.GREENFIELD, "r", T0);
        stopped.safeStop("x", null, null, T1);
        assertThatThrownBy(stopped::reopen)
                .hasMessage("run " + stopped.getId() + " cannot be re-planned while SAFE_STOPPED");
    }

    @Test
    void decisionCopiesTheLineageOfTheStageRevision() {
        StageExecution execution = new StageExecution("run-1", "design");
        execution.recordInputs("in", java.util.Map.of("requirements", "abc"));
        execution.start(T0);
        execution.recordCommit("sha1");
        execution.succeed("plan", T1);

        Decision decision = new Decision(execution, "claude-opus-5.5", T1);

        assertThat(decision.getRunId()).isEqualTo("run-1");
        assertThat(decision.getStageId()).isEqualTo("design");
        assertThat(decision.getRevision()).isEqualTo(1);
        assertThat(decision.getInputHash()).isEqualTo("in");
        assertThat(decision.getUpstreamHashes()).isEqualTo("requirements=abc");
        assertThat(decision.getOutputHash()).isEqualTo(execution.getOutputHash());
        assertThat(decision.getModel()).isEqualTo("claude-opus-5.5");
        assertThat(decision.getCommitSha()).isEqualTo("sha1");
        assertThat(decision.getRecordedAt()).isEqualTo(T1);
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

package com.veenaacharya.agentic.orchestrator.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/** State of one stage within a run. Transitions are guarded so illegal moves fail loudly. */
@Entity
@Table(name = "stage_execution")
public class StageExecution {

    static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Column(nullable = false)
    private String stageId;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StageStatus status;

    private Instant startedAt;

    private Instant finishedAt;

    @Lob
    private String output;

    @Column(length = MAX_ERROR_LENGTH)
    private String error;

    protected StageExecution() {
    }

    public StageExecution(String runId, String stageId) {
        this.runId = runId;
        this.stageId = stageId;
        this.attempt = 1;
        this.status = StageStatus.PENDING;
    }

    public void start(Instant at) {
        requireStatus(StageStatus.PENDING);
        status = StageStatus.RUNNING;
        startedAt = at;
    }

    public void succeed(String stageOutput, Instant at) {
        requireStatus(StageStatus.RUNNING);
        status = StageStatus.SUCCEEDED;
        output = stageOutput;
        finishedAt = at;
    }

    public void fail(String reason, Instant at) {
        requireStatus(StageStatus.RUNNING);
        status = StageStatus.FAILED;
        error = truncate(reason);
        finishedAt = at;
    }

    public void skip(Instant at) {
        requireStatus(StageStatus.PENDING);
        status = StageStatus.SKIPPED;
        finishedAt = at;
    }

    public void block(String reason) {
        requireStatus(StageStatus.PENDING);
        status = StageStatus.BLOCKED;
        error = truncate(reason);
    }

    /** True when downstream stages may proceed. */
    public boolean isSatisfied() {
        return status == StageStatus.SUCCEEDED || status == StageStatus.SKIPPED;
    }

    /** True when downstream stages can never proceed in this run. */
    public boolean isDeadEnd() {
        return status == StageStatus.FAILED || status == StageStatus.BLOCKED;
    }

    private void requireStatus(StageStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "stage " + stageId + " must be " + expected + " but is " + status);
        }
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_ERROR_LENGTH);
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getStageId() {
        return stageId;
    }

    public int getAttempt() {
        return attempt;
    }

    public StageStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }
}

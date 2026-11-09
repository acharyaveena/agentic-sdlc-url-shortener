package com.veenaacharya.agentic.orchestrator.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One try at a stage: which model ran, whether it succeeded, and why not. Feeds retry metrics and MTTR. */
@Entity
@Table(name = "stage_attempt")
public class StageAttempt {

    static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Column(nullable = false)
    private String stageId;

    @Column(nullable = false)
    private int attemptNumber;

    @Column(nullable = false, columnDefinition = "integer default 1")
    private int revision;

    /** Null for stages that do not call a model (build, policy scan). */
    private String model;

    @Column(nullable = false)
    private boolean succeeded;

    @Column(length = MAX_ERROR_LENGTH)
    private String error;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant finishedAt;

    protected StageAttempt() {
    }

    public StageAttempt(String runId, String stageId, int attemptNumber, String model, boolean succeeded,
                        String error, Instant startedAt, Instant finishedAt) {
        this(runId, stageId, 1, attemptNumber, model, succeeded, error, startedAt, finishedAt);
    }

    public StageAttempt(String runId, String stageId, int revision, int attemptNumber, String model,
                        boolean succeeded, String error, Instant startedAt, Instant finishedAt) {
        this.runId = runId;
        this.stageId = stageId;
        this.revision = revision;
        this.attemptNumber = attemptNumber;
        this.model = model;
        this.succeeded = succeeded;
        this.error = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
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

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public int getRevision() {
        return revision;
    }

    public String getModel() {
        return model;
    }

    public boolean isSucceeded() {
        return succeeded;
    }

    public String getError() {
        return error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}

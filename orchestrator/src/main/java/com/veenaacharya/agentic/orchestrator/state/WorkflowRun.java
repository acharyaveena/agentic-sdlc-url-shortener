package com.veenaacharya.agentic.orchestrator.state;

import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One execution of the stage graph for a single change request. */
@Entity
@Table(name = "workflow_run")
public class WorkflowRun {

    @Id
    private String id;

    @Column(nullable = false)
    private String scenario;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private ChangeType changeType;

    @Lob
    private String request;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private RunStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant finishedAt;

    /** Git branch holding this run's commits; null when the run is not tied to a repository. */
    private String branch;

    /** Commit the run branch started from; the rollback target when nothing was approved yet. */
    private String baseCommit;

    /** Failures to inject for demonstrations, e.g. {@code design:2,build-test:always}; null normally. */
    private String chaos;

    @Column(length = 2000)
    private String stopReason;

    private String rollbackTarget;

    /** Git ref that keeps the failed state reachable after a rollback. */
    private String preservedRef;

    protected WorkflowRun() {
    }

    public WorkflowRun(String scenario, ChangeType changeType, String request, Instant createdAt) {
        this.id = UUID.randomUUID().toString();
        this.scenario = scenario;
        this.changeType = changeType;
        this.request = request;
        this.status = RunStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void markRunning() {
        this.status = RunStatus.RUNNING;
    }

    public void markAwaitingApproval() {
        this.status = RunStatus.AWAITING_APPROVAL;
    }

    public void assignBranch(String branchName) {
        this.branch = branchName;
    }

    public void assignBaseCommit(String commit) {
        this.baseCommit = commit;
    }

    public void useChaos(String spec) {
        this.chaos = spec == null || spec.isBlank() ? null : spec;
    }

    public void safeStop(String reason, String target, String preserved, Instant at) {
        this.status = RunStatus.SAFE_STOPPED;
        this.stopReason = reason != null && reason.length() > 2000 ? reason.substring(0, 2000) : reason;
        this.rollbackTarget = target;
        this.preservedRef = preserved;
        this.finishedAt = at;
    }

    public boolean isFinished() {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.SAFE_STOPPED;
    }

    public String getBranch() {
        return branch;
    }

    public String getBaseCommit() {
        return baseCommit;
    }

    public String getChaos() {
        return chaos;
    }

    public String getStopReason() {
        return stopReason;
    }

    public String getRollbackTarget() {
        return rollbackTarget;
    }

    public String getPreservedRef() {
        return preservedRef;
    }

    public void finish(RunStatus finalStatus, Instant at) {
        this.status = finalStatus;
        this.finishedAt = at;
    }

    public String getId() {
        return id;
    }

    public String getScenario() {
        return scenario;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getRequest() {
        return request;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}

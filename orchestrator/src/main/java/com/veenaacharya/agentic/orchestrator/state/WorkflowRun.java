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

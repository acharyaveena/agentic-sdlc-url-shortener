package com.veenaacharya.agentic.orchestrator.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A human (or explicitly configured auto) decision on one stage's output. */
@Entity
@Table(name = "approval")
public class Approval {

    static final int MAX_REASON_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Column(nullable = false)
    private String stageId;

    @Column(nullable = false)
    private String kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private ApprovalStatus status;

    @Column(nullable = false)
    private Instant requestedAt;

    private String decidedBy;

    @Column(length = MAX_REASON_LENGTH)
    private String reason;

    private Instant decidedAt;

    protected Approval() {
    }

    public Approval(String runId, String stageId, String kind, Instant requestedAt) {
        this.runId = runId;
        this.stageId = stageId;
        this.kind = kind;
        this.status = ApprovalStatus.PENDING;
        this.requestedAt = requestedAt;
    }

    public void approve(String actor, String note, Instant at) {
        decide(ApprovalStatus.APPROVED, actor, note, at);
    }

    public void reject(String actor, String note, Instant at) {
        decide(ApprovalStatus.REJECTED, actor, note, at);
    }

    public void supersede(String actor, String note, Instant at) {
        decide(ApprovalStatus.SUPERSEDED, actor, note, at);
    }

    private void decide(ApprovalStatus decision, String actor, String note, Instant at) {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException(
                    "approval for stage " + stageId + " is already " + status);
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("approval needs an actor");
        }
        status = decision;
        decidedBy = actor;
        reason = note == null || note.length() <= MAX_REASON_LENGTH ? note : note.substring(0, MAX_REASON_LENGTH);
        decidedAt = at;
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

    public String getKind() {
        return kind;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public String getReason() {
        return reason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}

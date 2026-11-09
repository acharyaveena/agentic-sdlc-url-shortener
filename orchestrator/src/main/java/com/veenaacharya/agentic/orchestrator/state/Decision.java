package com.veenaacharya.agentic.orchestrator.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Lineage record: one row each time a stage produces an accepted output. It ties the output hash to
 * the inputs it was built from (upstream output hashes), the model that wrote it and the commit holding it,
 * so any output can be traced back and staleness can be explained.
 */
@Entity
@Table(name = "decision")
public class Decision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Column(nullable = false)
    private String stageId;

    @Column(nullable = false)
    private int revision;

    private String inputHash;

    @Column(length = 2000)
    private String upstreamHashes;

    @Column(nullable = false)
    private String outputHash;

    private String model;

    private String commitSha;

    @Column(nullable = false)
    private Instant recordedAt;

    protected Decision() {
    }

    public Decision(StageExecution stage, String model, Instant recordedAt) {
        this.runId = stage.getRunId();
        this.stageId = stage.getStageId();
        this.revision = stage.getRevision();
        this.inputHash = stage.getInputHash();
        this.upstreamHashes = stage.upstreamHashesText();
        this.outputHash = stage.getOutputHash();
        this.model = model;
        this.commitSha = stage.getCommitSha();
        this.recordedAt = recordedAt;
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

    public int getRevision() {
        return revision;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getUpstreamHashes() {
        return upstreamHashes;
    }

    public String getOutputHash() {
        return outputHash;
    }

    public String getModel() {
        return model;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}

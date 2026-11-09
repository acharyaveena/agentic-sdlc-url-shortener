package com.veenaacharya.agentic.orchestrator.state;

import com.veenaacharya.agentic.orchestrator.audit.Hashes;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    // Plain varchar so new statuses never need a schema migration (H2 enum columns are not widened by ddl-auto).
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private StageStatus status;

    private Instant startedAt;

    private Instant finishedAt;

    @Lob
    private String output;

    @Column(length = MAX_ERROR_LENGTH)
    private String error;

    /** Commit that recorded this stage's result; null until committed. */
    private String commitSha;

    /** Starts at 1 and goes up each time the stage is re-planned or goes stale and must run again. */
    @Column(nullable = false)
    private int revision;

    /** Hash of everything the stage was given: request, upstream outputs and any reviewer feedback. */
    private String inputHash;

    /** Output hash of each upstream stage this revision consumed, as {@code stage=hash;stage=hash}. */
    @Column(length = 2000)
    private String upstreamHashes;

    private String outputHash;

    /** Why the stage is being re-planned; handed to the model on the next run of the stage. */
    @Column(length = MAX_ERROR_LENGTH)
    private String replanFeedback;

    protected StageExecution() {
    }

    public StageExecution(String runId, String stageId) {
        this.runId = runId;
        this.stageId = stageId;
        this.attempt = 1;
        this.revision = 1;
        this.status = StageStatus.PENDING;
    }

    public void recordInputs(String hash, Map<String, String> upstream) {
        inputHash = hash;
        upstreamHashes = upstream.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
    }

    /** Output hash of each upstream stage this revision was built from. */
    public Map<String, String> consumedUpstream() {
        Map<String, String> consumed = new LinkedHashMap<>();
        if (upstreamHashes != null && !upstreamHashes.isEmpty()) {
            Arrays.stream(upstreamHashes.split(";")).map(e -> e.split("=", 2))
                    .forEach(e -> consumed.put(e[0], e[1]));
        }
        return consumed;
    }

    public void start(Instant at) {
        requireStatus(StageStatus.PENDING, StageStatus.STALE);
        status = StageStatus.RUNNING;
        startedAt = at;
        finishedAt = null;
        error = null;
    }

    public void succeed(String stageOutput, Instant at) {
        requireStatus(StageStatus.RUNNING);
        status = StageStatus.SUCCEEDED;
        setOutput(stageOutput);
        finishedAt = at;
    }

    public void awaitApproval(String stageOutput) {
        requireStatus(StageStatus.RUNNING);
        status = StageStatus.AWAITING_APPROVAL;
        setOutput(stageOutput);
    }

    public void approve(Instant at) {
        requireStatus(StageStatus.AWAITING_APPROVAL);
        status = StageStatus.SUCCEEDED;
        finishedAt = at;
    }

    public void reject(String reason, Instant at) {
        requireStatus(StageStatus.AWAITING_APPROVAL);
        status = StageStatus.FAILED;
        error = truncate(reason);
        finishedAt = at;
    }

    public void fail(String reason, Instant at) {
        requireStatus(StageStatus.RUNNING);
        status = StageStatus.FAILED;
        error = truncate(reason);
        finishedAt = at;
    }

    public void skip(Instant at) {
        requireStatus(StageStatus.PENDING, StageStatus.STALE);
        status = StageStatus.SKIPPED;
        finishedAt = at;
    }

    public void block(String reason) {
        requireStatus(StageStatus.PENDING, StageStatus.STALE);
        status = StageStatus.BLOCKED;
        error = truncate(reason);
    }

    /** An upstream output this stage was built from has changed, so the stage must run again. */
    public void markStale(String reason) {
        requireStatus(StageStatus.SUCCEEDED, StageStatus.AWAITING_APPROVAL);
        status = StageStatus.STALE;
        error = truncate(reason);
        replanFeedback = null;
        revision++;
    }

    /** A human asked for this stage to be redone; the feedback goes to the model on the next run. */
    public void replan(String feedback) {
        requireStatus(StageStatus.SUCCEEDED, StageStatus.AWAITING_APPROVAL, StageStatus.FAILED);
        status = StageStatus.PENDING;
        replanFeedback = truncate(feedback);
        error = null;
        startedAt = null;
        finishedAt = null;
        revision++;
    }

    /** Lets a stage that was blocked by an upstream failure run again once that upstream is re-planned. */
    public void reopen() {
        requireStatus(StageStatus.BLOCKED);
        status = StageStatus.PENDING;
        error = null;
    }

    /** True when downstream stages may proceed. */
    public boolean isSatisfied() {
        return status == StageStatus.SUCCEEDED || status == StageStatus.SKIPPED;
    }

    /** True when downstream stages can never proceed in this run. */
    public boolean isDeadEnd() {
        return status == StageStatus.FAILED || status == StageStatus.BLOCKED;
    }

    /** Number of attempts used, including retries and the fallback model. */
    public void recordAttempts(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("attempt count must be at least 1, was " + count);
        }
        attempt = count;
    }

    public void recordCommit(String sha) {
        commitSha = sha;
    }

    public String getCommitSha() {
        return commitSha;
    }

    private void setOutput(String stageOutput) {
        output = stageOutput;
        outputHash = Hashes.sha256(stageOutput);
    }

    private void requireStatus(StageStatus... allowed) {
        if (Arrays.stream(allowed).noneMatch(s -> s == status)) {
            throw new IllegalStateException("stage " + stageId + " must be "
                    + Arrays.stream(allowed).map(Enum::name).collect(Collectors.joining(" or "))
                    + " but is " + status);
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

    public int getRevision() {
        return revision;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getOutputHash() {
        return outputHash;
    }

    public String getReplanFeedback() {
        return replanFeedback;
    }

    String upstreamHashesText() {
        return upstreamHashes;
    }
}

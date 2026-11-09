package com.veenaacharya.agentic.orchestrator.audit;

import java.time.Instant;

/**
 * One line of the audit log. Every event carries the same keys so the log can be queried as a table;
 * keys that do not apply to an event are null.
 *
 * @param revision how many times the stage has been (re)planned in this run, starting at 1
 * @param decision outcome of the event, e.g. SUCCEEDED, FAILED, APPROVED, REJECTED, STALE
 * @param actor    who acted: {@code orchestrator}, {@code agent}, {@code auto}, or a human reviewer
 */
public record AuditEvent(String timestamp, String runId, String event, String stage, Integer revision,
                         Integer attempt, String model, String inputHash, String outputHash, String decision,
                         String actor, String detail) {

    public static Builder of(String event, String runId) {
        return new Builder(event, runId);
    }

    public static final class Builder {

        private final String event;
        private final String runId;
        private String stage;
        private Integer revision;
        private Integer attempt;
        private String model;
        private String inputHash;
        private String outputHash;
        private String decision;
        private String actor;
        private String detail;

        private Builder(String event, String runId) {
            this.event = event;
            this.runId = runId;
        }

        public Builder stage(String value, int stageRevision) {
            stage = value;
            revision = stageRevision;
            return this;
        }

        public Builder attempt(int value, String attemptModel) {
            attempt = value;
            model = attemptModel;
            return this;
        }

        public Builder inputHash(String value) {
            inputHash = value;
            return this;
        }

        public Builder outputHash(String value) {
            outputHash = value;
            return this;
        }

        public Builder decision(String value) {
            decision = value;
            return this;
        }

        public Builder actor(String value) {
            actor = value;
            return this;
        }

        public Builder detail(String value) {
            detail = value;
            return this;
        }

        public AuditEvent at(Instant time) {
            return new AuditEvent(time.toString(), runId, event, stage, revision, attempt, model, inputHash,
                    outputHash, decision, actor, detail);
        }
    }
}

package com.veenaacharya.agentic.orchestrator.engine;

/** Outcome of executing one stage. */
public record StageResult(boolean succeeded, String output, String error) {

    public static StageResult success(String output) {
        return new StageResult(true, output, null);
    }

    public static StageResult failure(String error) {
        return new StageResult(false, null, error);
    }
}

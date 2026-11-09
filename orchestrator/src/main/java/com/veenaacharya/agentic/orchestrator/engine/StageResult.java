package com.veenaacharya.agentic.orchestrator.engine;

import java.util.List;

/**
 * Outcome of executing one stage.
 *
 * @param files repository-relative paths the stage wrote; the engine commits exactly these
 */
public record StageResult(boolean succeeded, String output, String error, List<String> files) {

    public StageResult {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public StageResult(boolean succeeded, String output, String error) {
        this(succeeded, output, error, null);
    }

    public static StageResult success(String output) {
        return new StageResult(true, output, null);
    }

    public static StageResult success(String output, List<String> files) {
        return new StageResult(true, output, null, files);
    }

    public static StageResult failure(String error) {
        return new StageResult(false, null, error);
    }
}

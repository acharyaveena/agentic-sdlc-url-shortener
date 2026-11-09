package com.veenaacharya.agentic.orchestrator.state;

public enum StageStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    /** Not run because an upstream stage failed or was blocked. */
    BLOCKED
}

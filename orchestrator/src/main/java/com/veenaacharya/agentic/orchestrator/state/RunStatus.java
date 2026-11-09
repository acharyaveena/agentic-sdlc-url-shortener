package com.veenaacharya.agentic.orchestrator.state;

public enum RunStatus {
    PENDING,
    RUNNING,
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    /** A stage failed after all recovery attempts; the workspace was rolled back and the run stopped. */
    SAFE_STOPPED
}

package com.veenaacharya.agentic.orchestrator.state;

public enum StageStatus {
    PENDING,
    RUNNING,
    /** Output produced and gate-checked; a human must approve before downstream stages run. */
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    /** Not run because an upstream stage failed or was blocked. */
    BLOCKED,
    /** Built from an upstream output that has since changed; runs again, with its gates and approvals. */
    STALE
}

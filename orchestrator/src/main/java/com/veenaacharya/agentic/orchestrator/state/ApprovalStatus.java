package com.veenaacharya.agentic.orchestrator.state;

public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** No longer relevant because the stage output it was about was replaced before anyone decided. */
    SUPERSEDED
}

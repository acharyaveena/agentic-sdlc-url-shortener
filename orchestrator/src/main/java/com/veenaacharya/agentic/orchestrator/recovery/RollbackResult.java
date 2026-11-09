package com.veenaacharya.agentic.orchestrator.recovery;

/**
 * @param target       commit the workspace was reset to; null when nothing was rolled back
 * @param preservedRef ref that still points at the failed state; null when nothing was preserved
 */
public record RollbackResult(String target, String preservedRef) {

    public static final RollbackResult NOTHING = new RollbackResult(null, null);
}

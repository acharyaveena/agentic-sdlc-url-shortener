package com.veenaacharya.agentic.orchestrator.recovery;

import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;

/** Restores a run's workspace to a known-good point after it fails for good. */
public interface RollbackStrategy {

    RollbackStrategy NONE = (run, target) -> RollbackResult.NOTHING;

    RollbackResult rollback(WorkflowRun run, String targetCommit);
}

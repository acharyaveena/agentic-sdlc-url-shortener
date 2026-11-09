package com.veenaacharya.agentic.orchestrator.engine;

/** Does the work of a single stage. Called from worker threads, so implementations must be thread-safe. */
public interface StageExecutor {

    StageResult execute(StageContext context);
}

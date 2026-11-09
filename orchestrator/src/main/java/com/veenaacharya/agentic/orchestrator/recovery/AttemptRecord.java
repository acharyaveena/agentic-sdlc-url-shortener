package com.veenaacharya.agentic.orchestrator.recovery;

import java.time.Instant;

/** One try at a stage, recorded by the worker thread and persisted later by the engine. */
public record AttemptRecord(int number, String model, boolean succeeded, String error,
                            Instant startedAt, Instant finishedAt) {
}

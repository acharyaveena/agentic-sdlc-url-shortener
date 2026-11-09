package com.veenaacharya.agentic.orchestrator.recovery;

import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import java.util.List;

/** Final result of a stage after recovery, with every attempt that led to it. */
public record StageOutcome(StageResult result, List<AttemptRecord> attempts) {

    public StageOutcome {
        attempts = List.copyOf(attempts);
    }
}

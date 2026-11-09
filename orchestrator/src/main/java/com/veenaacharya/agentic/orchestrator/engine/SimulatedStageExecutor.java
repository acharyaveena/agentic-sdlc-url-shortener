package com.veenaacharya.agentic.orchestrator.engine;

import org.springframework.stereotype.Component;

/** Placeholder executor that returns canned output; replaced by agent-backed execution in a later section. */
@Component
public class SimulatedStageExecutor implements StageExecutor {

    @Override
    public StageResult execute(StageContext context) {
        return StageResult.success("[simulated] " + context.stage().id()
                + " | inputs=" + context.upstreamOutputs().keySet()
                + " | request=" + context.request());
    }
}

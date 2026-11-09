package com.veenaacharya.agentic.orchestrator.policy;

import java.util.Map;

/** What a rule may consult besides the files: which stage writes them and the approved upstream outputs. */
public record PolicyContext(String stageId, Map<String, String> upstreamOutputs) {

    public PolicyContext {
        upstreamOutputs = upstreamOutputs == null ? Map.of() : Map.copyOf(upstreamOutputs);
    }
}

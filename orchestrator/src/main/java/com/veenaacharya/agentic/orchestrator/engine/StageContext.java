package com.veenaacharya.agentic.orchestrator.engine;

import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input handed to a stage: the original request plus outputs of every upstream stage
 * that succeeded, in topological order.
 */
public record StageContext(
        String runId,
        StageDefinition stage,
        ChangeType changeType,
        String request,
        Map<String, String> upstreamOutputs) {

    public StageContext {
        upstreamOutputs = Collections.unmodifiableMap(new LinkedHashMap<>(upstreamOutputs));
    }
}

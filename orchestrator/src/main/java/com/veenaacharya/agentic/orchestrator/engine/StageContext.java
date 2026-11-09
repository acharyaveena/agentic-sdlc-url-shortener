package com.veenaacharya.agentic.orchestrator.engine;

import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input handed to a stage: the original request plus outputs of every upstream stage
 * that succeeded, in topological order.
 *
 * @param model         model to use for this attempt; null means the stage's configured model
 * @param retryFeedback why the previous attempt failed, so the model can correct it; null on a first attempt
 */
public record StageContext(
        String runId,
        StageDefinition stage,
        ChangeType changeType,
        String request,
        Map<String, String> upstreamOutputs,
        String model,
        String retryFeedback) {

    public StageContext {
        upstreamOutputs = Collections.unmodifiableMap(new LinkedHashMap<>(upstreamOutputs));
    }

    public StageContext(String runId, StageDefinition stage, ChangeType changeType, String request,
                        Map<String, String> upstreamOutputs) {
        this(runId, stage, changeType, request, upstreamOutputs, null, null);
    }

    public StageContext forAttempt(String attemptModel, String feedback) {
        return new StageContext(runId, stage, changeType, request, upstreamOutputs, attemptModel, feedback);
    }
}

package com.veenaacharya.agentic.orchestrator.agent;

/** One model call made on behalf of a stage. */
public record AgentRequest(String runId, String stageId, String model, String systemPrompt, String userPrompt) {
}

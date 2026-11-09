package com.veenaacharya.agentic.orchestrator.agent;

/** Calls an AI model and returns its text answer. Implementations must be thread-safe. */
public interface AgentRuntime {

    /** @throws AgentException if the model cannot be reached, times out or returns nothing usable */
    String complete(AgentRequest request);
}

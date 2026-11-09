package com.veenaacharya.agentic.orchestrator.policy;

/** One rule breach. The message never contains the offending secret value. */
public record Violation(String rule, String path, String message) {

    @Override
    public String toString() {
        return rule + ": " + path + " - " + message;
    }
}

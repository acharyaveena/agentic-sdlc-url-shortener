package com.veenaacharya.agentic.orchestrator.evidence;

import java.util.List;

/** Thrown when exported evidence would contain secrets, local paths or personal data. Nothing is written. */
public class EvidenceRejectedException extends RuntimeException {

    private final List<String> problems;

    public EvidenceRejectedException(List<String> problems) {
        super("evidence not exported, fix these first:\n- " + String.join("\n- ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}

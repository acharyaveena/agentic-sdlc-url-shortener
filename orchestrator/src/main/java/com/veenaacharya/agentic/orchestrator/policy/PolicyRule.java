package com.veenaacharya.agentic.orchestrator.policy;

import java.util.List;

/** A single guardrail. */
public interface PolicyRule {

    String name();

    List<Violation> check(List<FileChange> changes, PolicyContext context);
}

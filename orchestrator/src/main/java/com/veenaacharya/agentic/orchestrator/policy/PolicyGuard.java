package com.veenaacharya.agentic.orchestrator.policy;

import java.util.List;

/** Decides whether a set of file changes may be applied. An empty result means allowed. */
public interface PolicyGuard {

    List<Violation> check(List<FileChange> changes, PolicyContext context);

    List<String> ruleNames();
}

package com.veenaacharya.agentic.orchestrator.workflow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One node of the stage graph as declared in {@code workflow.yml}. */
public record StageDefinition(String id, List<String> dependsOn, Set<ChangeType> skipFor) {

    public StageDefinition {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(new LinkedHashSet<>(dependsOn));
        skipFor = skipFor == null ? Set.of() : Set.copyOf(skipFor);
    }

    public boolean isSkippedFor(ChangeType changeType) {
        return skipFor.contains(changeType);
    }
}

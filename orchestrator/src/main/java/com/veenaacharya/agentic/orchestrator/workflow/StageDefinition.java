package com.veenaacharya.agentic.orchestrator.workflow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One node of the stage graph as declared in {@code workflow.yml}.
 *
 * @param requiredSections exit gate: markdown headings the stage output must contain
 * @param approval         approval kind a human must grant before downstream stages run; null if none
 */
public record StageDefinition(String id, List<String> dependsOn, Set<ChangeType> skipFor,
                              List<String> requiredSections, String approval) {

    public StageDefinition {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(new LinkedHashSet<>(dependsOn));
        skipFor = skipFor == null ? Set.of() : Set.copyOf(skipFor);
        requiredSections = requiredSections == null ? List.of() : List.copyOf(requiredSections);
        approval = approval == null || approval.isBlank() ? null : approval;
    }

    public StageDefinition(String id, List<String> dependsOn, Set<ChangeType> skipFor) {
        this(id, dependsOn, skipFor, null, null);
    }

    public boolean isSkippedFor(ChangeType changeType) {
        return skipFor.contains(changeType);
    }
}

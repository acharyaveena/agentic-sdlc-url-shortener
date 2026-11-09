package com.veenaacharya.agentic.orchestrator.workflow;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The full stage graph. Use {@link WorkflowValidator} before executing it. */
public record WorkflowDefinition(String name, List<StageDefinition> stages) {

    public WorkflowDefinition {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    public StageDefinition stage(String id) {
        return stages.stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown stage: " + id));
    }

    /** All stages that {@code id} depends on, directly or transitively. */
    public Set<String> ancestorsOf(String id) {
        Set<String> ancestors = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(stage(id).dependsOn());
        while (!pending.isEmpty()) {
            String next = pending.pop();
            if (ancestors.add(next)) {
                pending.addAll(stage(next).dependsOn());
            }
        }
        return ancestors;
    }
}

package com.veenaacharya.agentic.orchestrator.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Checks that a workflow is a DAG and returns a deterministic topological order. */
public class WorkflowValidator {

    public List<String> validate(WorkflowDefinition workflow) {
        if (workflow == null || workflow.stages().isEmpty()) {
            throw new WorkflowValidationException("workflow has no stages");
        }

        Map<String, StageDefinition> byId = new LinkedHashMap<>();
        for (StageDefinition stage : workflow.stages()) {
            if (stage.id() == null || stage.id().isBlank()) {
                throw new WorkflowValidationException("stage id must not be blank");
            }
            if (byId.putIfAbsent(stage.id(), stage) != null) {
                throw new WorkflowValidationException("duplicate stage id: " + stage.id());
            }
        }

        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (StageDefinition stage : byId.values()) {
            inDegree.put(stage.id(), stage.dependsOn().size());
            for (String dependency : stage.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    throw new WorkflowValidationException(
                            "stage '" + stage.id() + "' depends on unknown stage '" + dependency + "'");
                }
                dependents.computeIfAbsent(dependency, k -> new ArrayList<>()).add(stage.id());
            }
        }

        // Kahn's algorithm; FIFO keeps declaration order among stages that become ready together.
        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (order.size() != byId.size()) {
            List<String> unresolved = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            throw new WorkflowValidationException("cycle detected; unresolved stages: " + unresolved);
        }
        return List.copyOf(order);
    }
}

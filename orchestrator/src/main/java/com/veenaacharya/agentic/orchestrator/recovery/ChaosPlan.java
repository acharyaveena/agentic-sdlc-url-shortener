package com.veenaacharya.agentic.orchestrator.recovery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Failures to inject on purpose, to demonstrate recovery: {@code <stage>:<count|always>} entries separated
 * by commas. A count fails that many first attempts; {@code always} fails every attempt.
 */
public record ChaosPlan(Map<String, Integer> failures) {

    public static final ChaosPlan NONE = new ChaosPlan(Map.of());
    private static final int ALWAYS = -1;

    public ChaosPlan {
        failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    public static ChaosPlan parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return NONE;
        }
        Map<String, Integer> failures = new LinkedHashMap<>();
        for (String entry : spec.split(",")) {
            String[] parts = entry.strip().split(":", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw invalid(entry);
            }
            String stage = parts[0].strip();
            String count = parts[1].strip();
            int value;
            if (count.equalsIgnoreCase("always")) {
                value = ALWAYS;
            } else {
                try {
                    value = Integer.parseInt(count);
                } catch (NumberFormatException e) {
                    throw invalid(entry);
                }
                if (value < 1) {
                    throw invalid(entry);
                }
            }
            if (failures.putIfAbsent(stage, value) != null) {
                throw new IllegalArgumentException("chaos names stage '" + stage + "' twice");
            }
        }
        return new ChaosPlan(failures);
    }

    public boolean shouldFail(String stageId, int attemptNumber) {
        Integer count = failures.get(stageId);
        return count != null && (count == ALWAYS || attemptNumber <= count);
    }

    public boolean isActive() {
        return !failures.isEmpty();
    }

    public Set<String> stageIds() {
        return failures.keySet();
    }

    /** Canonical form, also used to store the plan on the run. */
    public String spec() {
        return failures.entrySet().stream()
                .map(e -> e.getKey() + ":" + (e.getValue() == ALWAYS ? "always" : e.getValue()))
                .collect(Collectors.joining(","));
    }

    private static IllegalArgumentException invalid(String entry) {
        return new IllegalArgumentException(
                "invalid chaos entry '" + entry.strip() + "'; expected <stage>:<count|always>, count at least 1");
    }
}

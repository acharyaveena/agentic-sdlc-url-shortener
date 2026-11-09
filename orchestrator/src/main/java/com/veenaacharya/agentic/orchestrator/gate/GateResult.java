package com.veenaacharya.agentic.orchestrator.gate;

import java.util.List;

/** Outcome of a gate check; {@code reasons} explains each failure. */
public record GateResult(boolean passed, List<String> reasons) {

    public GateResult {
        reasons = List.copyOf(reasons);
    }

    public static GateResult pass() {
        return new GateResult(true, List.of());
    }

    public static GateResult fail(List<String> reasons) {
        return new GateResult(false, reasons);
    }

    public String summary() {
        return String.join("; ", reasons);
    }
}

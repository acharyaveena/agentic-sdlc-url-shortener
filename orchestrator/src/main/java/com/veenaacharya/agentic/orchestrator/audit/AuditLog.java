package com.veenaacharya.agentic.orchestrator.audit;

import java.nio.file.Path;
import java.util.Optional;

/** Append-only record of everything that happened in a run. Writing must not fail silently. */
public interface AuditLog {

    String ORCHESTRATOR = "orchestrator";
    String AGENT = "agent";

    AuditLog NONE = event -> {
    };

    void record(AuditEvent event);

    /** Where the run's log is stored, if it is stored in a file. */
    default Optional<Path> fileFor(String runId) {
        return Optional.empty();
    }
}

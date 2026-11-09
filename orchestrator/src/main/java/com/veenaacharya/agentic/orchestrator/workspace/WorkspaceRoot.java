package com.veenaacharya.agentic.orchestrator.workspace;

import java.nio.file.Path;

/** Absolute path of the repository root that contains {@code url-shortener/}. */
public record WorkspaceRoot(Path path) {
}

package com.veenaacharya.agentic.orchestrator.policy;

/** A file an agent wants to write: repository-relative path (forward slashes) and full content. */
public record FileChange(String path, String content) {
}

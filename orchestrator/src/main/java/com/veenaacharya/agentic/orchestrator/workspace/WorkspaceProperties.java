package com.veenaacharya.agentic.orchestrator.workspace;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param root                repository root; blank means the enclosing git repository of the working directory
 * @param buildCommand        command that builds and tests the product; empty means the Maven wrapper on {@code url-shortener}
 * @param buildTimeoutSeconds limit for the build
 */
@ConfigurationProperties("orchestrator.workspace")
public record WorkspaceProperties(String root, List<String> buildCommand, long buildTimeoutSeconds) {

    public WorkspaceProperties {
        buildCommand = buildCommand == null ? List.of() : List.copyOf(buildCommand);
        buildTimeoutSeconds = buildTimeoutSeconds <= 0 ? 600 : buildTimeoutSeconds;
    }
}

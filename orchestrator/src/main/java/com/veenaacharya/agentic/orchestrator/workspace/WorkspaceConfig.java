package com.veenaacharya.agentic.orchestrator.workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WorkspaceProperties.class)
public class WorkspaceConfig {

    @Bean
    public WorkspaceRoot workspaceRoot(WorkspaceProperties properties) {
        if (properties.root() != null && !properties.root().isBlank()) {
            return new WorkspaceRoot(Path.of(properties.root()).toAbsolutePath().normalize());
        }
        ProcessRunner.Result result = ProcessRunner.run(Path.of("").toAbsolutePath(), Duration.ofSeconds(15),
                List.of("git", "rev-parse", "--show-toplevel"));
        if (!result.ok()) {
            throw new IllegalStateException(
                    "not inside a git repository; set orchestrator.workspace.root to the repository root");
        }
        return new WorkspaceRoot(Path.of(result.output().strip()).toAbsolutePath().normalize());
    }
}

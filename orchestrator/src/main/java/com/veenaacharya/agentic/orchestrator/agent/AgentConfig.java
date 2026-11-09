package com.veenaacharya.agentic.orchestrator.agent;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

    // Spring closes the Copilot runtime on shutdown because it implements AutoCloseable.
    @Bean
    public AgentRuntime agentRuntime(AgentProperties properties) {
        return switch (properties.mode()) {
            case "mock" -> new MockAgentRuntime();
            case "copilot" -> new CopilotSdkAgentRuntime(properties.timeoutSeconds());
            default -> throw new IllegalArgumentException(
                    "orchestrator.agent.mode must be mock or copilot, was: " + properties.mode());
        };
    }
}

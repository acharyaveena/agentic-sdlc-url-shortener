package com.veenaacharya.agentic.orchestrator.agent;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode           {@code mock} (default, offline) or {@code copilot}
 * @param defaultModel   model used when a stage has no entry in {@code models}
 * @param models         model id per stage id
 * @param timeoutSeconds per-call limit for model requests
 */
@ConfigurationProperties("orchestrator.agent")
public record AgentProperties(String mode, String defaultModel, Map<String, String> models, long timeoutSeconds) {

    public AgentProperties {
        mode = mode == null || mode.isBlank() ? "mock" : mode;
        defaultModel = defaultModel == null || defaultModel.isBlank() ? "claude-sonnet-5.5" : defaultModel;
        models = models == null ? Map.of() : Map.copyOf(models);
        timeoutSeconds = timeoutSeconds <= 0 ? 300 : timeoutSeconds;
    }

    public String modelFor(String stageId) {
        return models.getOrDefault(stageId, defaultModel);
    }
}

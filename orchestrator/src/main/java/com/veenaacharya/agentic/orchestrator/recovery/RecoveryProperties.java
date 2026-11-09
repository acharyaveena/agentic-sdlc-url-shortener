package com.veenaacharya.agentic.orchestrator.recovery;

import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxAttempts    tries on the stage's own model before falling back
 * @param fallbackModels model to use after those tries fail, keyed by the original model
 */
@ConfigurationProperties("orchestrator.recovery")
public record RecoveryProperties(int maxAttempts, Map<String, String> fallbackModels) {

    public RecoveryProperties {
        maxAttempts = maxAttempts <= 0 ? 2 : maxAttempts;
        fallbackModels = fallbackModels == null ? Map.of() : Map.copyOf(fallbackModels);
    }

    public Optional<String> fallbackFor(String model) {
        String fallback = model == null ? null : fallbackModels.get(model);
        return fallback == null || fallback.equals(model) ? Optional.empty() : Optional.of(fallback);
    }
}

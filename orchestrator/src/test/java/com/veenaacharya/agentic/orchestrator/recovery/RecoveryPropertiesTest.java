package com.veenaacharya.agentic.orchestrator.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RecoveryPropertiesTest {

    @Test
    void defaultsToTwoAttemptsAndNoFallbacks() {
        RecoveryProperties properties = new RecoveryProperties(0, null);

        assertThat(properties.maxAttempts()).isEqualTo(2);
        assertThat(properties.fallbackModels()).isEmpty();
        assertThat(properties.fallbackFor("claude-opus-5.5")).isEmpty();
    }

    @Test
    void fallbackIsLookedUpByModelAndIgnoresSelfMappingAndNull() {
        RecoveryProperties properties = new RecoveryProperties(3,
                Map.of("claude-opus-5.5", "claude-sonnet-5.5", "same", "same"));

        assertThat(properties.maxAttempts()).isEqualTo(3);
        assertThat(properties.fallbackFor("claude-opus-5.5")).contains("claude-sonnet-5.5");
        assertThat(properties.fallbackFor("same")).isEmpty();
        assertThat(properties.fallbackFor("unknown")).isEmpty();
        assertThat(properties.fallbackFor(null)).isEmpty();
    }
}

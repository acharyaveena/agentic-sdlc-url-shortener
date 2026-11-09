package com.veenaacharya.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPropertiesTest {

    @Test
    void appliesDefaultsForMissingValues() {
        AgentProperties properties = new AgentProperties(null, " ", null, 0);

        assertThat(properties.mode()).isEqualTo("mock");
        assertThat(properties.defaultModel()).isEqualTo("claude-sonnet-5.5");
        assertThat(properties.models()).isEmpty();
        assertThat(properties.timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void modelForUsesStageOverrideThenDefault() {
        AgentProperties properties = new AgentProperties("copilot", "sonnet", Map.of("design", "opus"), 60);

        assertThat(properties.modelFor("design")).isEqualTo("opus");
        assertThat(properties.modelFor("code")).isEqualTo("sonnet");
        assertThat(properties.mode()).isEqualTo("copilot");
        assertThat(properties.timeoutSeconds()).isEqualTo(60);
    }
}

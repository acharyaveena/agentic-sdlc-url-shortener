package com.veenaacharya.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MockAgentRuntimeTest {

    private static AgentRequest request(String stage) {
        return new AgentRequest("run-1", stage, "claude-sonnet-5.5", "system", "user");
    }

    @Test
    void returnsDeterministicOutputAndRecordsRequests() {
        MockAgentRuntime runtime = new MockAgentRuntime();

        String output = runtime.complete(request("custom-stage"));

        assertThat(output).isEqualTo("# custom-stage (mock output)\n\nmodel: claude-sonnet-5.5");
        assertThat(runtime.requests()).containsExactly(request("custom-stage"));
    }

    @Test
    void bundledOutputIsServedForKnownStages() {
        String output = new MockAgentRuntime().complete(request("requirements"));

        assertThat(output).contains("## Problem statement", "## Ambiguities", "## Assumptions",
                "## Acceptance criteria");
    }

    @Test
    void cannedOutputOverridesDefault() {
        MockAgentRuntime runtime = new MockAgentRuntime().respondWith("code", "FILE: x");

        assertThat(runtime.complete(request("code"))).isEqualTo("FILE: x");
        assertThat(runtime.complete(request("other"))).startsWith("# other");
    }

    @Test
    void failingStageThrowsAgentException() {
        MockAgentRuntime runtime = new MockAgentRuntime().failOn("code");

        assertThatThrownBy(() -> runtime.complete(request("code")))
                .isInstanceOf(AgentException.class)
                .hasMessage("mock failure for stage code");
        assertThat(runtime.requests()).hasSize(1);
        assertThat(runtime.complete(request("other"))).isNotBlank();
    }

    @Test
    void requestListIsACopy() {
        MockAgentRuntime runtime = new MockAgentRuntime();
        runtime.complete(request("a"));
        List<AgentRequest> snapshot = runtime.requests();
        runtime.complete(request("b"));

        assertThat(snapshot).hasSize(1);
    }
}

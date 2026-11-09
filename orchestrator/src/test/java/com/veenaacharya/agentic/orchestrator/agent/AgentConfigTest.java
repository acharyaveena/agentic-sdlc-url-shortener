package com.veenaacharya.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.SessionConfig;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

    private final AgentConfig config = new AgentConfig();

    @Test
    void mockModeCreatesMockRuntime() {
        AgentRuntime runtime = config.agentRuntime(new AgentProperties("mock", null, null, 0));

        assertThat(runtime).isInstanceOf(MockAgentRuntime.class);
    }

    @Test
    void copilotModeCreatesSdkRuntimeWithoutStartingIt() {
        AgentRuntime runtime = config.agentRuntime(new AgentProperties("copilot", null, null, 30));

        assertThat(runtime).isInstanceOf(CopilotSdkAgentRuntime.class);
        ((CopilotSdkAgentRuntime) runtime).close();
    }

    @Test
    void unknownModeIsRejected() {
        assertThatThrownBy(() -> config.agentRuntime(new AgentProperties("gpt", null, null, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orchestrator.agent.mode must be mock or copilot, was: gpt");
    }

    @Test
    void copilotMessageCarriesTheUserPrompt() {
        AgentRequest request = new AgentRequest("run-1", "design", "claude-opus-5.5", "SYSTEM", "USER PROMPT");

        assertThat(CopilotSdkAgentRuntime.messageOptions(request).getPrompt()).isEqualTo("USER PROMPT");
    }

    @Test
    void copilotSessionIsConfiguredWithModelAndAppendedSystemPrompt() {
        AgentRequest request = new AgentRequest("run-1", "design", "claude-opus-5.5", "SYSTEM", "USER");

        SessionConfig session = CopilotSdkAgentRuntime.sessionConfig(request);

        assertThat(session.getModel()).isEqualTo("claude-opus-5.5");
        assertThat(session.getSystemMessage().getMode()).isEqualTo(SystemMessageMode.APPEND);
        assertThat(session.getSystemMessage().getContent()).isEqualTo("SYSTEM");
        assertThat(session.getOnPermissionRequest()).isNotNull();
    }
}

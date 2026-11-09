package com.veenaacharya.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentStageExecutorTest {

    private final AgentProperties properties =
            new AgentProperties("mock", "sonnet", Map.of("requirements", "opus"), 60);

    private static StageContext context(String stageId) {
        return new StageContext("run-1", new StageDefinition(stageId, List.of(), Set.of()),
                ChangeType.GREENFIELD, "shorten urls", Map.of("requirements", "REQ"));
    }

    @Test
    void sendsPromptForStageToRuntimeAndReturnsItsAnswer() {
        MockAgentRuntime runtime = new MockAgentRuntime().respondWith("requirements", "# answer");

        StageResult result = new AgentStageExecutor(runtime, properties).execute(context("requirements"));

        assertThat(result).isEqualTo(StageResult.success("# answer"));
        AgentRequest sent = runtime.requests().get(0);
        assertThat(sent.model()).isEqualTo("opus");
        assertThat(sent.userPrompt()).contains("shorten urls").contains("## Output of stage: requirements\nREQ");
    }

    @Test
    void runtimeFailureBecomesStageFailure() {
        MockAgentRuntime runtime = new MockAgentRuntime().failOn("code");

        StageResult result = new AgentStageExecutor(runtime, properties).execute(context("code"));

        assertThat(result).isEqualTo(StageResult.failure("mock failure for stage code"));
    }

    @Test
    void missingPromptTemplateBecomesStageFailure() {
        StageResult result = new AgentStageExecutor(new MockAgentRuntime(), properties).execute(context("unknown"));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).isEqualTo("no prompt template for stage unknown (prompts/unknown.md)");
    }

    @Test
    void stageResultFactoriesSetFields() {
        assertThat(StageResult.success("o")).isEqualTo(new StageResult(true, "o", null));
        assertThat(StageResult.failure("e")).isEqualTo(new StageResult(false, null, "e"));
    }
}

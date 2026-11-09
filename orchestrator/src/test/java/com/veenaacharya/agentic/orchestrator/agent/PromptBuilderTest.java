package com.veenaacharya.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {

    private final AgentProperties properties =
            new AgentProperties("mock", "sonnet", Map.of("design", "opus"), 60);
    private final PromptBuilder builder = new PromptBuilder(properties);

    @Test
    void everyModelBackedStageHasAPromptTemplate() {
        WorkflowDefinition workflow = new WorkflowLoader().loadFromClasspath("workflow.yml");
        List<StageDefinition> modelBacked = workflow.stages().stream()
                .filter(s -> s.kind().equals("agent") || s.kind().equals("files")).toList();

        assertThat(modelBacked).extracting(StageDefinition::id).containsExactly(
                "requirements", "impact-analysis", "design", "code", "tests", "docs", "release-readiness");
        for (StageDefinition stage : modelBacked) {
            AgentRequest request = builder.build(context(stage.id(), Map.of()));

            assertThat(request.systemPrompt()).as(stage.id()).startsWith("You are the ");
        }
    }

    @Test
    void requestCarriesRunStageModelAndSystemPrompt() {
        AgentRequest request = builder.build(context("design", Map.of()));

        assertThat(request.runId()).isEqualTo("run-1");
        assertThat(request.stageId()).isEqualTo("design");
        assertThat(request.model()).isEqualTo("opus");
        assertThat(request.systemPrompt()).contains("## Task plan");
        assertThat(builder.build(context("code", Map.of())).model()).isEqualTo("sonnet");
    }

    @Test
    void userPromptListsRequestChangeTypeAndUpstreamOutputsInOrder() {
        Map<String, String> upstream = new LinkedHashMap<>();
        upstream.put("requirements", "R-OUT");
        upstream.put("design", "D-OUT");

        String prompt = PromptBuilder.userPrompt(context("code", upstream));

        assertThat(prompt).isEqualTo("""
                ## Change request
                add link expiry

                ## Change type
                brownfield

                ## Output of stage: requirements
                R-OUT

                ## Output of stage: design
                D-OUT
                """);
    }

    @Test
    void missingTemplateIsReported() {
        StageContext context = new StageContext("run-1", new StageDefinition("nope", List.of(), Set.of()),
                ChangeType.GREENFIELD, "x", Map.of());

        assertThatThrownBy(() -> builder.build(context))
                .isInstanceOf(AgentException.class)
                .hasMessage("no prompt template for stage nope (prompts/nope.md)");
    }

    private static StageContext context(String stageId, Map<String, String> upstream) {
        return new StageContext("run-1", new StageDefinition(stageId, List.of(), Set.of()),
                ChangeType.BROWNFIELD, "add link expiry", upstream);
    }
}

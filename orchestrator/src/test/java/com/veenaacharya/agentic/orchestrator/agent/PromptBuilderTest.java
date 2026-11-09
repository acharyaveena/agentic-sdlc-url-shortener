package com.veenaacharya.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.policy.FileChange;
import com.veenaacharya.agentic.orchestrator.policy.PolicyContext;
import com.veenaacharya.agentic.orchestrator.policy.WritePathRule;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void retryCarriesTheFailureReasonAndTheAttemptModelOverridesTheStageModel() {
        StageContext retry = context("design", Map.of()).forAttempt("fallback-model", "exit gate failed: [Task plan]");

        AgentRequest request = builder.build(retry);

        assertThat(request.model()).isEqualTo("fallback-model");
        assertThat(request.userPrompt()).endsWith("""

                ## Previous attempt failed
                exit gate failed: [Task plan]

                Correct this problem in your answer and follow the required format exactly.
                """);
    }

    @Test
    void longFeedbackIsTruncated() {
        String prompt = PromptBuilder.userPrompt(context("code", Map.of()).forAttempt(null, "x".repeat(10_000)));

        assertThat(prompt).contains("x".repeat(PromptBuilder.MAX_FEEDBACK_CHARS) + "\n[truncated]")
                .doesNotContain("x".repeat(PromptBuilder.MAX_FEEDBACK_CHARS + 1));
        assertThat(builder.build(context("code", Map.of()).forAttempt(null, "y")).model()).isEqualTo("sonnet");
    }

    @Test
    void codePromptAsksForThePomWithTheRealParentCoordinatesSoTheDesignedDependenciesCanBeBuilt() throws IOException {
        String prompt = builder.build(context("code", Map.of())).systemPrompt();

        assertThat(prompt).contains("`url-shortener/pom.xml`", "<artifactId>url-shortener</artifactId>");
        // The prompt may only ask for what the write-path policy lets the code stage write.
        assertThat(new WritePathRule().check(List.of(new FileChange("url-shortener/pom.xml", "x")),
                new PolicyContext("code", Map.of()))).isEmpty();

        Path rootPom = Path.of("..", "pom.xml");
        assumeTrue(Files.exists(rootPom), "root pom not reachable from this working directory");
        String pom = Files.readString(rootPom);
        String artifactId = pom.replaceFirst("(?s).*?</parent>\\s*<groupId>[^<]+</groupId>\\s*<artifactId>([^<]+)</artifactId>.*", "$1");
        String version = pom.replaceFirst("(?s).*?</parent>.*?<version>([^<]+)</version>.*", "$1");
        assertThat(prompt).contains("<artifactId>" + artifactId + "</artifactId>", "<version>" + version + "</version>");
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

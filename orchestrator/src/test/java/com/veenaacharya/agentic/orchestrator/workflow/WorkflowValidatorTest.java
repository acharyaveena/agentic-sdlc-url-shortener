package com.veenaacharya.agentic.orchestrator.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowValidatorTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    @Test
    void returnsTopologicalOrderKeepingDeclarationOrderForParallelStages() {
        WorkflowDefinition workflow = new WorkflowLoader().loadFromClasspath("workflow.yml");

        assertThat(validator.validate(workflow)).containsExactly(
                "requirements", "impact-analysis", "design", "code", "tests",
                "build-test", "docs", "policy-scan", "release-readiness");
    }

    @Test
    void orderDoesNotDependOnDeclarationOrderOfDependencies() {
        WorkflowDefinition workflow = workflow(
                stage("c", "b"),
                stage("b", "a"),
                stage("a"));

        assertThat(validator.validate(workflow)).containsExactly("a", "b", "c");
    }

    @Test
    void rejectsCycle() {
        WorkflowDefinition workflow = workflow(
                stage("start"),
                stage("a", "start", "b"),
                stage("b", "a"));

        assertThatThrownBy(() -> validator.validate(workflow))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessage("cycle detected; unresolved stages: [a, b]");
    }

    @Test
    void rejectsSelfDependency() {
        WorkflowDefinition workflow = workflow(stage("a", "a"));

        assertThatThrownBy(() -> validator.validate(workflow))
                .hasMessage("cycle detected; unresolved stages: [a]");
    }

    @Test
    void rejectsUnknownDependency() {
        WorkflowDefinition workflow = workflow(stage("design", "requirments"));

        assertThatThrownBy(() -> validator.validate(workflow))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessage("stage 'design' depends on unknown stage 'requirments'");
    }

    @Test
    void rejectsDuplicateStageId() {
        WorkflowDefinition workflow = workflow(stage("code"), stage("code"));

        assertThatThrownBy(() -> validator.validate(workflow))
                .hasMessage("duplicate stage id: code");
    }

    @Test
    void rejectsBlankStageId() {
        WorkflowDefinition workflow = workflow(stage(" "));

        assertThatThrownBy(() -> validator.validate(workflow))
                .hasMessage("stage id must not be blank");
    }

    @Test
    void rejectsEmptyWorkflow() {
        assertThatThrownBy(() -> validator.validate(new WorkflowDefinition("empty", List.of())))
                .hasMessage("workflow has no stages");
    }

    private static WorkflowDefinition workflow(StageDefinition... stages) {
        return new WorkflowDefinition("test", List.of(stages));
    }

    private static StageDefinition stage(String id, String... dependsOn) {
        return new StageDefinition(id, List.of(dependsOn), Set.of());
    }
}

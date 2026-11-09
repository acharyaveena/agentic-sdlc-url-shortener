package com.veenaacharya.agentic.orchestrator.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WorkflowLoaderTest {

    private final WorkflowLoader loader = new WorkflowLoader();

    @Test
    void loadsBundledWorkflow() {
        WorkflowDefinition workflow = loader.loadFromClasspath("workflow.yml");

        assertThat(workflow.name()).isEqualTo("sdlc");
        assertThat(workflow.stages()).hasSize(9);
        assertThat(workflow.stage("requirements").dependsOn()).isEmpty();
        assertThat(workflow.stage("impact-analysis").skipFor()).containsExactly(ChangeType.GREENFIELD);
        assertThat(workflow.stage("build-test").dependsOn()).containsExactly("code", "tests");
        assertThat(workflow.stage("release-readiness").dependsOn()).containsExactly("docs", "policy-scan");
    }

    @Test
    void bundledWorkflowDeclaresGatesAndApprovals() {
        WorkflowDefinition workflow = loader.loadFromClasspath("workflow.yml");

        assertThat(workflow.stage("requirements").requiredSections())
                .containsExactly("Problem statement", "Ambiguities", "Assumptions", "Acceptance criteria");
        assertThat(workflow.stages().stream().filter(s -> s.approval() != null)
                .map(s -> s.id() + "=" + s.approval()))
                .containsExactly("requirements=requirements-signoff", "design=design-and-schema",
                        "release-readiness=release");
        assertThat(workflow.stage("code").approval()).isNull();
        assertThat(workflow.stage("code").requiredSections()).isEmpty();
    }

    @Test
    void parsesEnumsCaseInsensitively() {
        WorkflowDefinition workflow = loader.load(yaml("""
                name: w
                stages:
                  - id: a
                    skipFor: [BROWNFIELD, greenfield]
                """));

        assertThat(workflow.stage("a").skipFor())
                .containsExactlyInAnyOrder(ChangeType.BROWNFIELD, ChangeType.GREENFIELD);
    }

    @Test
    void rejectsUnknownKeys() {
        assertThatThrownBy(() -> loader.load(yaml("""
                name: w
                stages:
                  - id: a
                    dependOn: [b]
                """)))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("dependOn");
    }

    @Test
    void reportsMissingResource() {
        assertThatThrownBy(() -> loader.loadFromClasspath("missing.yml"))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessage("workflow resource not found: missing.yml");
    }

    private static ByteArrayInputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}

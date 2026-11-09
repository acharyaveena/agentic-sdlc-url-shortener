package com.veenaacharya.agentic.orchestrator.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionTest {

    private final WorkflowDefinition workflow = new WorkflowLoader().loadFromClasspath("workflow.yml");

    @Test
    void ancestorsIncludeTransitiveDependencies() {
        assertThat(workflow.ancestorsOf("build-test"))
                .containsExactlyInAnyOrder("code", "tests", "design", "impact-analysis", "requirements");
    }

    @Test
    void ancestorsOfRootAreEmpty() {
        assertThat(workflow.ancestorsOf("requirements")).isEmpty();
    }

    @Test
    void unknownStageIsRejected() {
        assertThatThrownBy(() -> workflow.stage("deploy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown stage: deploy");
    }

    @Test
    void stageDefinitionNormalisesNullsAndDuplicateDependencies() {
        StageDefinition stage = new StageDefinition("x", Arrays.asList("a", "b", "a"), null);

        assertThat(stage.dependsOn()).containsExactly("a", "b");
        assertThat(stage.skipFor()).isEmpty();
        assertThat(new StageDefinition("y", null, null).dependsOn()).isEmpty();
    }

    @Test
    void stageIsSkippedOnlyForListedChangeTypes() {
        StageDefinition stage = new StageDefinition("impact", null, Set.of(ChangeType.GREENFIELD));

        assertThat(stage.isSkippedFor(ChangeType.GREENFIELD)).isTrue();
        assertThat(stage.isSkippedFor(ChangeType.BROWNFIELD)).isFalse();
    }
}

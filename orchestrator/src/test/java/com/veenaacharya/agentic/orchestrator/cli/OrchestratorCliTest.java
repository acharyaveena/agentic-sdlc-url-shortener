package com.veenaacharya.agentic.orchestrator.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class OrchestratorCliTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final WorkflowEngine engine = mock(WorkflowEngine.class);
    private final StageExecutionRepository stageExecutions = mock(StageExecutionRepository.class);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final OrchestratorCli cli =
            new OrchestratorCli(engine, stageExecutions, new PrintStream(buffer, true, StandardCharsets.UTF_8));

    @Test
    void printsUsageWithoutScenario() {
        cli.run(new DefaultApplicationArguments());

        assertThat(output()).isEqualTo(OrchestratorCli.USAGE + System.lineSeparator());
        verifyNoInteractions(engine);
    }

    @Test
    void runsScenarioAndPrintsStageStatuses() {
        WorkflowRun run = new WorkflowRun("greenfield", ChangeType.GREENFIELD, "build it", NOW);
        run.finish(RunStatus.SUCCEEDED, NOW);
        when(engine.run("greenfield", ChangeType.GREENFIELD, "build it")).thenReturn(run);
        StageExecution skipped = new StageExecution(run.getId(), "impact-analysis");
        skipped.skip(NOW);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of(skipped));

        cli.run(new DefaultApplicationArguments(
                "--scenario=greenfield", "--change-type=greenfield", "--request=build it"));

        verify(engine).run("greenfield", ChangeType.GREENFIELD, "build it");
        assertThat(output())
                .contains("Run " + run.getId() + " (greenfield, greenfield) finished: SUCCEEDED")
                .contains("impact-analysis    SKIPPED");
    }

    @Test
    void defaultsToBrownfieldWithEmptyRequest() {
        WorkflowRun run = new WorkflowRun("expiry", ChangeType.BROWNFIELD, "", NOW);
        when(engine.run("expiry", ChangeType.BROWNFIELD, "")).thenReturn(run);
        when(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).thenReturn(List.of());

        cli.run(new DefaultApplicationArguments("--scenario=expiry"));

        verify(engine).run("expiry", ChangeType.BROWNFIELD, "");
    }

    @Test
    void rejectsUnknownChangeType() {
        assertThatThrownBy(() -> cli.run(new DefaultApplicationArguments(
                "--scenario=x", "--change-type=bluefield")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--change-type must be greenfield or brownfield, was: bluefield");
        verifyNoInteractions(engine);
    }

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }
}

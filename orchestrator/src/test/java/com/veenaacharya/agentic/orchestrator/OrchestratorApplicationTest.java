package com.veenaacharya.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:orchestrator-app;DB_CLOSE_DELAY=-1")
class OrchestratorApplicationTest {

    @Autowired
    private WorkflowDefinition workflow;

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private StageExecutionRepository stageExecutions;

    @Test
    void startupLoadsAndValidatesBundledWorkflow() {
        assertThat(workflow.name()).isEqualTo("sdlc");
        assertThat(workflow.stages()).hasSize(9);
    }

    @Test
    void wiredEngineRunsFullGraphWithSimulatedExecutor() {
        WorkflowRun run = engine.run("smoke", ChangeType.GREENFIELD, "shorten urls");

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).hasSize(9);
    }
}

package com.veenaacharya.agentic.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutingStageExecutorTest {

    private final AgentStageExecutor agent = mock(AgentStageExecutor.class);
    private final FileWritingStageExecutor files = mock(FileWritingStageExecutor.class);
    private final BuildStageExecutor build = mock(BuildStageExecutor.class);
    private final PolicyScanStageExecutor policyScan = mock(PolicyScanStageExecutor.class);
    private final RoutingStageExecutor router = new RoutingStageExecutor(agent, files, build, policyScan);

    private static StageContext context(String kind) {
        return new StageContext("run-1", new StageDefinition("s", List.of(), Set.of(), List.of(), null, kind),
                ChangeType.GREENFIELD, "req", Map.of());
    }

    @Test
    void routesEachKindToItsExecutor() {
        when(agent.execute(any())).thenReturn(StageResult.success("agent"));
        when(files.execute(any())).thenReturn(StageResult.success("files"));
        when(build.execute(any())).thenReturn(StageResult.success("build"));
        when(policyScan.execute(any())).thenReturn(StageResult.success("scan"));

        assertThat(router.execute(context("agent")).output()).isEqualTo("agent");
        assertThat(router.execute(context("files")).output()).isEqualTo("files");
        assertThat(router.execute(context("build")).output()).isEqualTo("build");
        assertThat(router.execute(context("policy-scan")).output()).isEqualTo("scan");
    }

    @Test
    void stageWithoutAKindUsesTheAgentAndTouchesNoWorkspaceExecutor() {
        when(agent.execute(any())).thenReturn(StageResult.success("agent"));

        assertThat(router.execute(context(null)).output()).isEqualTo("agent");
        verifyNoInteractions(files, build, policyScan);
    }
}

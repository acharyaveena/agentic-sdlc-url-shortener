package com.veenaacharya.agentic.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildStageExecutorTest {

    @TempDir
    Path root;

    private static final StageContext CONTEXT = new StageContext("run-1",
            new StageDefinition("build-test", List.of(), Set.of()), ChangeType.GREENFIELD, "req", Map.of());

    private BuildStageExecutor executor(List<String> command, long timeoutSeconds) {
        return new BuildStageExecutor(new WorkspaceRoot(root), new WorkspaceProperties(null, command, timeoutSeconds));
    }

    @Test
    void successfulBuildReportsExitCodeAndLogTail() {
        StageResult result = executor(TestProcesses.run(TestProcesses.Echo.class, "0", "BUILD", "SUCCESS"), 30)
                .execute(CONTEXT);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).startsWith("## Build result").contains("Exit code: 0").contains("BUILD SUCCESS");
    }

    @Test
    void failedBuildFailsTheStageWithTheLogTail() {
        StageResult result = executor(TestProcesses.run(TestProcesses.Echo.class, "1", "COMPILATION", "ERROR"), 30)
                .execute(CONTEXT);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).startsWith("build failed (exit 1):").contains("COMPILATION ERROR");
    }

    @Test
    void buildThatRunsTooLongIsKilledAndFailed() {
        StageResult result = executor(TestProcesses.run(TestProcesses.Sleep.class), 1).execute(CONTEXT);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).startsWith("build timed out after 1s");
    }

    @Test
    void localRepositoryPathIsReplacedInStoredOutput() {
        String rootPath = root.toString();

        StageResult result = executor(TestProcesses.run(TestProcesses.Echo.class, "0", "at", rootPath, "done"), 30)
                .execute(CONTEXT);

        assertThat(result.output()).contains("at <repo> done").doesNotContain(rootPath);
    }

    @Test
    void onlyTheLastFortyLinesOfALongLogAreKept() {
        StageResult result = executor(TestProcesses.run(TestProcesses.Lines.class, "100"), 30).execute(CONTEXT);

        assertThat(result.output()).contains("line100", "line61").doesNotContain("line60\n");
    }

    @Test
    void propertiesApplyDefaults() {
        WorkspaceProperties defaults = new WorkspaceProperties(null, null, 0);

        assertThat(defaults.buildCommand()).isEmpty();
        assertThat(defaults.buildTimeoutSeconds()).isEqualTo(600);
        assertThat(defaults.root()).isNull();
    }
}

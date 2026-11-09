package com.veenaacharya.agentic.orchestrator.engine;

import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import java.util.List;

/** Where a run's file changes live. The engine only depends on this port, not on git. */
public interface RunWorkspace {

    RunWorkspace NONE = new RunWorkspace() {
        @Override
        public String startRun(WorkflowRun run) {
            return null;
        }

        @Override
        public void resumeRun(WorkflowRun run) {
        }

        @Override
        public void commitStage(String runId, String stageId, String output, List<String> files) {
        }
    };

    /** Isolates a new run (for git: a fresh branch) and returns its name; fails if the workspace is not clean. */
    String startRun(WorkflowRun run);

    /** Makes sure a continuing run works on its own branch. */
    void resumeRun(WorkflowRun run);

    /** Records one stage's result as one commit containing exactly the given files. */
    void commitStage(String runId, String stageId, String output, List<String> files);
}

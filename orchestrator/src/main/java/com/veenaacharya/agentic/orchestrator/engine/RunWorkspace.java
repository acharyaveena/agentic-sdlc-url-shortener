package com.veenaacharya.agentic.orchestrator.engine;

import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import java.util.List;

/** Where a run's file changes live. The engine only depends on this port, not on git. */
public interface RunWorkspace {

    /** Branch the run works on and the commit it started from; both null when not tied to a repository. */
    record Start(String branch, String baseCommit) {
    }

    RunWorkspace NONE = new RunWorkspace() {
        @Override
        public Start startRun(WorkflowRun run) {
            return new Start(null, null);
        }

        @Override
        public void resumeRun(WorkflowRun run) {
        }

        @Override
        public String commitStage(String runId, String stageId, String output, List<String> files) {
            return null;
        }
    };

    /** Isolates a new run (for git: a fresh branch); fails if the workspace is not clean. */
    Start startRun(WorkflowRun run);

    /** Makes sure a continuing run works on its own branch. */
    void resumeRun(WorkflowRun run);

    /** Records one stage's result as one commit containing exactly the given files; returns the commit id. */
    String commitStage(String runId, String stageId, String output, List<String> files);
}

package com.veenaacharya.agentic.orchestrator.recovery;

import com.veenaacharya.agentic.orchestrator.policy.WritePathRule;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.workspace.Git;
import com.veenaacharya.agentic.orchestrator.workspace.WorkspaceRoot;
import org.springframework.stereotype.Component;

/**
 * Tags the failed state as {@code agent-failed/<run id>} so a human can still inspect it, then resets the
 * run branch to the target commit and removes leftover untracked files in the product directory.
 * Only ever touches the run's own branch.
 */
@Component
public class GitRollbackStrategy implements RollbackStrategy {

    private final Git git;

    public GitRollbackStrategy(WorkspaceRoot root) {
        this.git = new Git(root.path());
    }

    @Override
    public RollbackResult rollback(WorkflowRun run, String targetCommit) {
        if (run.getBranch() == null || targetCommit == null) {
            return RollbackResult.NOTHING;
        }
        String current = git.currentBranch();
        if (!current.equals(run.getBranch())) {
            throw new IllegalStateException("refusing to roll back: HEAD is on " + current
                    + ", not on the run branch " + run.getBranch());
        }
        String tag = "agent-failed/" + run.getId().substring(0, 8);
        git.run("-c", "tag.gpgSign=false", "tag", "-f", tag, "HEAD");
        git.run("reset", "--hard", targetCommit);
        git.run("clean", "-fd", "--", WritePathRule.PRODUCT_DIR);
        return new RollbackResult(targetCommit, tag);
    }
}

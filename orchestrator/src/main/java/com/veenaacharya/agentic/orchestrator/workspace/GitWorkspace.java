package com.veenaacharya.agentic.orchestrator.workspace;

import com.veenaacharya.agentic.orchestrator.audit.Hashes;
import com.veenaacharya.agentic.orchestrator.engine.RunWorkspace;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** One branch per run ({@code agent/<scenario>-<run id>}) and one commit per stage, authored as the agent. */
@Component
public class GitWorkspace implements RunWorkspace {

    static final String AGENT_NAME = "agentic-orchestrator";
    static final String AGENT_EMAIL = "orchestrator@users.noreply.github.com";

    private final Git git;

    public GitWorkspace(WorkspaceRoot root) {
        this.git = new Git(root.path());
    }

    @Override
    public Start startRun(WorkflowRun run) {
        git.requireClean();
        String base = git.head();
        String branch = "agent/" + slug(run.getScenario()) + "-" + run.getId().substring(0, 8);
        git.run("checkout", "-b", branch);
        return new Start(branch, base);
    }

    @Override
    public void resumeRun(WorkflowRun run) {
        if (run.getBranch() == null || run.getBranch().equals(git.currentBranch())) {
            return;
        }
        git.requireClean();
        git.run("checkout", run.getBranch());
    }

    @Override
    public String commitStage(String runId, String stageId, String output, List<String> files) {
        if (!files.isEmpty()) {
            List<String> add = new ArrayList<>(List.of("add", "--"));
            add.addAll(files);
            git.run(add.toArray(String[]::new));
        }
        String subject = "chore(agent): " + stageId + " output [run " + runId.substring(0, 8) + "]";
        StringBuilder body = new StringBuilder()
                .append("Run: ").append(runId).append('\n')
                .append("Stage: ").append(stageId).append('\n')
                .append("Output-SHA256: ").append(Hashes.sha256(output)).append('\n');
        if (!files.isEmpty()) {
            body.append("Files:\n");
            files.forEach(f -> body.append("- ").append(f).append('\n'));
        }
        git.run("-c", "user.name=" + AGENT_NAME, "-c", "user.email=" + AGENT_EMAIL, "-c", "commit.gpgsign=false",
                "commit", "--allow-empty", "-m", subject, "-m", body.toString().strip());
        return git.head();
    }

    static String slug(String text) {
        String slug = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        slug = slug.length() > 30 ? slug.substring(0, 30).replaceAll("-+$", "") : slug;
        return slug.isEmpty() ? "run" : slug;
    }
}

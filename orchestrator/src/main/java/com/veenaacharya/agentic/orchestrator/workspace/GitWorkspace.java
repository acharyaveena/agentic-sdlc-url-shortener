package com.veenaacharya.agentic.orchestrator.workspace;

import com.veenaacharya.agentic.orchestrator.audit.Hashes;
import com.veenaacharya.agentic.orchestrator.engine.RunWorkspace;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** One branch per run ({@code agent/<scenario>-<run id>}) and one commit per stage, authored as the agent. */
@Component
public class GitWorkspace implements RunWorkspace {

    static final String AGENT_NAME = "agentic-orchestrator";
    static final String AGENT_EMAIL = "orchestrator@users.noreply.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Path root;

    public GitWorkspace(WorkspaceRoot root) {
        this.root = root.path();
    }

    @Override
    public String startRun(WorkflowRun run) {
        requireClean();
        String branch = "agent/" + slug(run.getScenario()) + "-" + run.getId().substring(0, 8);
        git("checkout", "-b", branch);
        return branch;
    }

    @Override
    public void resumeRun(WorkflowRun run) {
        if (run.getBranch() == null || run.getBranch().equals(git("rev-parse", "--abbrev-ref", "HEAD").strip())) {
            return;
        }
        requireClean();
        git("checkout", run.getBranch());
    }

    @Override
    public void commitStage(String runId, String stageId, String output, List<String> files) {
        if (!files.isEmpty()) {
            List<String> add = new ArrayList<>(List.of("add", "--"));
            add.addAll(files);
            git(add.toArray(String[]::new));
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
        git("-c", "user.name=" + AGENT_NAME, "-c", "user.email=" + AGENT_EMAIL, "-c", "commit.gpgsign=false",
                "commit", "--allow-empty", "-m", subject, "-m", body.toString().strip());
    }

    private void requireClean() {
        String status = git("status", "--porcelain").strip();
        if (!status.isEmpty()) {
            throw new IllegalStateException("the working tree has uncommitted changes; commit or stash them "
                    + "before an agent run switches branches:\n" + status);
        }
    }

    private String git(String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessRunner.Result result = ProcessRunner.run(root, TIMEOUT, command);
        if (!result.ok()) {
            throw new IllegalStateException("git " + subcommand(args) + " failed (exit " + result.exitCode() + "): "
                    + result.output().strip());
        }
        return result.output();
    }

    private static String subcommand(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-c")) {
                i++;
            } else {
                return args[i];
            }
        }
        return "";
    }

    static String slug(String text) {
        String slug = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        slug = slug.length() > 30 ? slug.substring(0, 30).replaceAll("-+$", "") : slug;
        return slug.isEmpty() ? "run" : slug;
    }
}

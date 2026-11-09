package com.veenaacharya.agentic.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.audit.Hashes;
import com.veenaacharya.agentic.orchestrator.engine.RunWorkspace;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs against a real throwaway git repository. */
class GitWorkspaceTest {

    @TempDir
    Path repo;

    private GitWorkspace workspace;

    private static String git(Path dir, String... args) {
        return TestProcesses.git(dir, args);
    }

    private static WorkflowRun run(String scenario) {
        return new WorkflowRun(scenario, ChangeType.GREENFIELD, "req", Instant.parse("2026-01-01T00:00:00Z"));
    }

    @BeforeEach
    void setUp() throws IOException {
        TestProcesses.initRepo(repo);
        workspace = new GitWorkspace(new WorkspaceRoot(repo));
    }

    @Test
    void startRunCreatesAndChecksOutABranchNamedAfterScenarioAndRun() {
        WorkflowRun run = run("Greenfield URL Shortener!");
        String mainHead = git(repo, "rev-parse", "HEAD");

        RunWorkspace.Start start = workspace.startRun(run);

        assertThat(start.branch()).isEqualTo("agent/greenfield-url-shortener-" + run.getId().substring(0, 8));
        assertThat(start.baseCommit()).isEqualTo(mainHead);
        assertThat(git(repo, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(start.branch());
    }

    @Test
    void startRunRefusesADirtyWorkingTree() throws IOException {
        Files.writeString(repo.resolve("notes.txt"), "uncommitted");

        assertThatThrownBy(() -> workspace.startRun(run("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uncommitted changes")
                .hasMessageContaining("notes.txt");
        assertThat(git(repo, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("main");

        Files.delete(repo.resolve("notes.txt"));
        Files.writeString(repo.resolve("README.md"), "modified\n");
        assertThatThrownBy(() -> workspace.startRun(run("x"))).hasMessageContaining("README.md");
    }

    @Test
    void commitStageRecordsExactlyTheGivenFilesAsTheAgent() throws IOException {
        workspace.startRun(run("s"));
        Files.createDirectories(repo.resolve("url-shortener"));
        Files.writeString(repo.resolve("url-shortener/A.java"), "class A {}\n");
        Files.writeString(repo.resolve("url-shortener/B.java"), "class B {}\n");

        String sha = workspace.commitStage("0123456789abcdef", "code", "the output", List.of("url-shortener/A.java"));

        assertThat(sha).isEqualTo(git(repo, "rev-parse", "HEAD")).hasSize(40);
        assertThat(git(repo, "log", "-1", "--format=%s")).isEqualTo("chore(agent): code output [run 01234567]");
        assertThat(git(repo, "log", "-1", "--format=%an <%ae>"))
                .isEqualTo("agentic-orchestrator <orchestrator@users.noreply.github.com>");
        assertThat(git(repo, "show", "--name-only", "--format=", "HEAD")).isEqualTo("url-shortener/A.java");
        assertThat(git(repo, "status", "--porcelain")).isEqualTo("?? url-shortener/B.java");
        String body = git(repo, "log", "-1", "--format=%b");
        assertThat(body).contains("Run: 0123456789abcdef", "Stage: code",
                "Output-SHA256: " + Hashes.sha256("the output"), "- url-shortener/A.java");
    }

    @Test
    void stageWithoutFilesStillGetsItsOwnCommit() {
        workspace.startRun(run("s"));

        workspace.commitStage("0123456789abcdef", "requirements", "text", List.of());
        workspace.commitStage("0123456789abcdef", "design", "more text", List.of());

        assertThat(git(repo, "log", "--format=%s", "main..HEAD").lines().toList()).containsExactly(
                "chore(agent): design output [run 01234567]", "chore(agent): requirements output [run 01234567]");
    }

    @Test
    void resumeRunSwitchesBackToTheRunBranch() {
        WorkflowRun run = run("s");
        run.assignBranch(workspace.startRun(run).branch());
        git(repo, "checkout", "-q", "main");

        workspace.resumeRun(run);

        assertThat(git(repo, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(run.getBranch());
    }

    @Test
    void resumeRunDoesNothingWhenAlreadyOnBranchOrRunHasNoBranch() {
        WorkflowRun branchless = run("s");
        workspace.resumeRun(branchless);
        assertThat(git(repo, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("main");

        WorkflowRun run = run("s");
        run.assignBranch(workspace.startRun(run).branch());
        workspace.resumeRun(run);
        assertThat(git(repo, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(run.getBranch());
    }

    @Test
    void resumeRunRefusesToSwitchWithUncommittedChanges() throws IOException {
        WorkflowRun run = run("s");
        run.assignBranch(workspace.startRun(run).branch());
        git(repo, "checkout", "-q", "main");
        Files.writeString(repo.resolve("scratch.txt"), "x");

        assertThatThrownBy(() -> workspace.resumeRun(run)).hasMessageContaining("uncommitted changes");
    }

    @Test
    void gitFailuresAreReportedWithTheSubcommand() {
        WorkflowRun run = run("s");
        run.assignBranch("agent/does-not-exist");

        assertThatThrownBy(() -> workspace.resumeRun(run))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("git checkout failed (exit ");
    }

    @Test
    void slugNormalisesScenarioNames() {
        assertThat(GitWorkspace.slug("Link Expiry!")).isEqualTo("link-expiry");
        assertThat(GitWorkspace.slug("  --Make Analytics Better--  ")).isEqualTo("make-analytics-better");
        assertThat(GitWorkspace.slug("***")).isEqualTo("run");
        assertThat(GitWorkspace.slug(null)).isEqualTo("run");
        assertThat(GitWorkspace.slug("a".repeat(10) + "-" + "b".repeat(40))).hasSize(30).doesNotEndWith("-");
    }
}

package com.veenaacharya.agentic.orchestrator.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.engine.RunWorkspace;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workspace.GitWorkspace;
import com.veenaacharya.agentic.orchestrator.workspace.TestProcesses;
import com.veenaacharya.agentic.orchestrator.workspace.WorkspaceRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs against a real throwaway git repository. */
class GitRollbackStrategyTest {

    @TempDir
    Path repo;

    private GitWorkspace workspace;
    private GitRollbackStrategy rollback;
    private WorkflowRun run;
    private RunWorkspace.Start start;

    @BeforeEach
    void setUp() throws IOException {
        TestProcesses.initRepo(repo);
        workspace = new GitWorkspace(new WorkspaceRoot(repo));
        rollback = new GitRollbackStrategy(new WorkspaceRoot(repo));
        run = new WorkflowRun("s", ChangeType.GREENFIELD, "req", Instant.parse("2026-01-01T00:00:00Z"));
        start = workspace.startRun(run);
        run.assignBranch(start.branch());
        run.assignBaseCommit(start.baseCommit());
    }

    private String git(String... args) {
        return TestProcesses.git(repo, args);
    }

    private void write(String path, String content) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void resetsToTargetKeepsFailedStateUnderTagAndCleansProductLeftovers() throws IOException {
        String design = workspace.commitStage(run.getId(), "design", "plan", List.of());
        write("url-shortener/src/main/java/A.java", "class A {}");
        String code = workspace.commitStage(run.getId(), "code", "code", List.of("url-shortener/src/main/java/A.java"));
        write("url-shortener/src/main/java/Leftover.java", "class Leftover {}");
        write("notes.txt", "a human's scratch file outside the product");

        RollbackResult result = rollback.rollback(run, design);

        String tag = "agent-failed/" + run.getId().substring(0, 8);
        assertThat(result).isEqualTo(new RollbackResult(design, tag));
        assertThat(git("rev-parse", "HEAD")).isEqualTo(design);
        assertThat(git("rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(run.getBranch());
        assertThat(git("rev-parse", tag)).isEqualTo(code);
        assertThat(repo.resolve("url-shortener/src/main/java/A.java")).doesNotExist();
        assertThat(repo.resolve("url-shortener/src/main/java/Leftover.java")).doesNotExist();
        assertThat(repo.resolve("notes.txt")).exists();
    }

    @Test
    void rollingBackToTheBaseCommitRemovesEveryRunCommit() throws IOException {
        workspace.commitStage(run.getId(), "requirements", "r", List.of());
        write("url-shortener/README.md", "# x");
        workspace.commitStage(run.getId(), "docs", "d", List.of("url-shortener/README.md"));

        rollback.rollback(run, run.getBaseCommit());

        assertThat(git("rev-parse", "HEAD")).isEqualTo(start.baseCommit());
        assertThat(git("log", "--format=%s", "main..HEAD")).isEmpty();
        assertThat(git("status", "--porcelain")).isEmpty();
    }

    @Test
    void refusesToTouchAnyBranchOtherThanTheRunBranch() {
        String design = workspace.commitStage(run.getId(), "design", "plan", List.of());
        git("checkout", "-q", "main");
        String mainHead = git("rev-parse", "HEAD");

        assertThatThrownBy(() -> rollback.rollback(run, design))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("refusing to roll back: HEAD is on main, not on the run branch " + run.getBranch());
        assertThat(git("rev-parse", "HEAD")).isEqualTo(mainHead);
        assertThat(git("tag", "--list")).isEmpty();
    }

    @Test
    void doesNothingWithoutABranchOrTarget() {
        WorkflowRun branchless = new WorkflowRun("s", ChangeType.GREENFIELD, "req", Instant.now());

        assertThat(rollback.rollback(branchless, "abc")).isEqualTo(RollbackResult.NOTHING);
        assertThat(rollback.rollback(run, null)).isEqualTo(RollbackResult.NOTHING);
        assertThat(RollbackStrategy.NONE.rollback(run, "abc")).isEqualTo(RollbackResult.NOTHING);
        assertThat(git("tag", "--list")).isEmpty();
    }
}

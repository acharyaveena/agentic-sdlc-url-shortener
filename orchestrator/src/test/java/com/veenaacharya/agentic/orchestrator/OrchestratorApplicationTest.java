package com.veenaacharya.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import com.veenaacharya.agentic.orchestrator.workspace.TestProcesses;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Whole application wired together, mock agent, real git repository, real file writing and policy scan. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orchestrator-app;DB_CLOSE_DELAY=-1",
        "orchestrator.approval.auto-approve=true"})
class OrchestratorApplicationTest {

    private static final Path REPO = createRepo();

    @DynamicPropertySource
    static void workspace(DynamicPropertyRegistry registry) {
        registry.add("orchestrator.workspace.root", REPO::toString);
        // Stands in for the real Maven build so the test does not start Maven inside Maven.
        List<String> build = TestProcesses.run(TestProcesses.Echo.class, "0", "BUILD", "SUCCESS");
        for (int i = 0; i < build.size(); i++) {
            String value = build.get(i);
            registry.add("orchestrator.workspace.build-command[" + i + "]", () -> value);
        }
    }

    @Autowired
    private WorkflowDefinition workflow;

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private StageExecutionRepository stageExecutions;

    private static Path createRepo() {
        try {
            Path repo = Files.createTempDirectory("orchestrator-e2e");
            TestProcesses.initRepo(repo);
            return repo;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void deleteRepo() throws IOException {
        Files.walkFileTree(REPO, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                file.toFile().setWritable(true);
                file.toFile().delete();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                dir.toFile().delete();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test
    void startupLoadsAndValidatesBundledWorkflow() {
        assertThat(workflow.name()).isEqualTo("sdlc");
        assertThat(workflow.stages()).hasSize(9);
    }

    @Test
    void mockRunBuildsPolicyChecksAndCommitsEachStageThenPausesOnlyForRelease() {
        WorkflowRun run = engine.run("e2e", ChangeType.GREENFIELD, "shorten urls");

        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(run.getBranch()).isEqualTo("agent/e2e-" + run.getId().substring(0, 8));
        assertThat(TestProcesses.git(REPO, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(run.getBranch());
        assertThat(stage(run, "build-test").getOutput()).contains("Exit code: 0", "BUILD SUCCESS");
        assertThat(stage(run, "policy-scan").getOutput())
                .contains("| secret-scan | PASS |", "| dependency-allowlist | PASS |", "## Verdict\nPASS");
        assertThat(stage(run, "release-readiness").getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);

        assertThat(REPO.resolve("url-shortener/src/main/java/com/veenaacharya/shortener/ShortCodeGenerator.java"))
                .exists();
        assertThat(REPO.resolve("url-shortener/src/test/java/com/veenaacharya/shortener/ShortCodeGeneratorTest.java"))
                .exists();
        assertThat(REPO.resolve("url-shortener/README.md")).exists();
        assertThat(TestProcesses.git(REPO, "status", "--porcelain")).isEmpty();

        List<String> commits = TestProcesses.git(REPO, "log", "--reverse", "--format=%s", "main..HEAD")
                .lines().map(s -> s.replaceAll("^chore\\(agent\\): | output \\[run .*$", "")).toList();
        assertThat(commits).containsExactlyInAnyOrder("requirements", "design", "code", "tests", "build-test",
                "docs", "policy-scan", "release-readiness");
        assertThat(commits.indexOf("requirements")).isLessThan(commits.indexOf("design"));
        assertThat(commits.indexOf("design")).isLessThan(Math.min(commits.indexOf("code"), commits.indexOf("tests")));
        assertThat(commits.indexOf("build-test")).isGreaterThan(Math.max(commits.indexOf("code"), commits.indexOf("tests")));
        assertThat(commits.indexOf("release-readiness")).isEqualTo(commits.size() - 1);
        assertThat(TestProcesses.git(REPO, "show", "--name-only", "--format=",
                "HEAD~" + (commits.size() - 1 - commits.indexOf("code"))))
                .isEqualTo("url-shortener/src/main/java/com/veenaacharya/shortener/ShortCodeGenerator.java");

        approvals.approve(run.getId(), "release-readiness", "tester", "ok");
        run = engine.resume(run.getId());

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stageExecutions.findByRunIdOrderByIdAsc(run.getId())).hasSize(9);
    }

    private StageExecution stage(WorkflowRun run, String stageId) {
        return stageExecutions.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(s -> s.getStageId().equals(stageId)).findFirst().orElseThrow();
    }
}

package com.veenaacharya.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.agent.AgentRequest;
import com.veenaacharya.agentic.orchestrator.agent.AgentRuntime;
import com.veenaacharya.agentic.orchestrator.agent.MockAgentRuntime;
import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.replan.ReplanService;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageAttempt;
import com.veenaacharya.agentic.orchestrator.state.StageAttemptRepository;
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
import org.junit.jupiter.api.BeforeEach;
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
    private static final Path AUDIT = createAuditDir();

    @DynamicPropertySource
    static void workspace(DynamicPropertyRegistry registry) {
        registry.add("orchestrator.workspace.root", REPO::toString);
        registry.add("orchestrator.audit.dir", AUDIT::toString);
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

    @Autowired
    private StageAttemptRepository attempts;

    @Autowired
    private AgentRuntime runtime;

    @Autowired
    private ReplanService replans;

    @Autowired
    private ApprovalRepository approvalRepository;

    @BeforeEach
    void startFromMain() {
        TestProcesses.git(REPO, "checkout", "-q", "main");
    }

    private static Path createAuditDir() {
        try {
            return Files.createTempDirectory("orchestrator-audit");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

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
        for (Path root : List.of(REPO, AUDIT)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
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
    }

    @Test
    void replanningDesignRerunsEverythingBuiltOnItUnderTheSameGovernanceAndAuditsIt() throws IOException {
        WorkflowRun run = engine.run("e2e-replan", ChangeType.GREENFIELD, "shorten urls");
        String firstDesign = stage(run, "design").getOutputHash();

        run = replans.replan(run.getId(), "design", "reviewer", "add rate limiting to the plan");

        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(stage(run, "requirements").getRevision()).isEqualTo(1);
        assertThat(stage(run, "design").getRevision()).isEqualTo(2);
        assertThat(stage(run, "design").getOutputHash()).isNotEqualTo(firstDesign);
        for (String rerun : List.of("code", "tests", "build-test", "docs", "policy-scan", "release-readiness")) {
            assertThat(stage(run, rerun).getRevision()).as(rerun).isEqualTo(2);
        }
        assertThat(stage(run, "release-readiness").getStatus()).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(approvalRepository.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(a -> a.getStageId().equals("release-readiness")).map(a -> a.getStatus().name()).toList())
                .containsExactly("SUPERSEDED", "PENDING");
        assertThat(TestProcesses.git(REPO, "log", "--format=%s", "main..HEAD").lines()
                .filter(s -> s.startsWith("chore(agent): design output")).count()).isEqualTo(2);

        Path auditFile = AUDIT.resolve(run.getId() + ".jsonl");
        String runId = run.getId();
        List<com.fasterxml.jackson.databind.JsonNode> events = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String line : Files.readAllLines(auditFile)) {
            events.add(mapper.readTree(line));
        }
        assertThat(events).allSatisfy(e -> {
            assertThat(e.get("runId").asText()).isEqualTo(runId);
            assertThat(e.has("stage") && e.has("attempt") && e.has("model") && e.has("inputHash")
                    && e.has("outputHash") && e.has("decision") && e.has("actor")).isTrue();
        });
        List<String> names = events.stream().map(e -> e.get("event").asText()).toList();
        assertThat(names).containsSubsequence("RUN_STARTED", "RUN_PAUSED", "REPLAN_REQUESTED", "RUN_RESUMED",
                "STAGE_STALE", "RUN_PAUSED");
        assertThat(events).filteredOn(e -> e.get("event").asText().equals("REPLAN_REQUESTED")).singleElement()
                .satisfies(e -> {
                    assertThat(e.get("actor").asText()).isEqualTo("reviewer");
                    assertThat(e.get("detail").asText()).isEqualTo("add rate limiting to the plan");
                    assertThat(e.get("outputHash").asText()).isEqualTo(firstDesign);
                });
        assertThat(events).filteredOn(e -> e.get("event").asText().equals("STAGE_STALE"))
                .extracting(e -> e.get("stage").asText())
                .contains("code", "tests", "build-test", "release-readiness");
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

    @Test
    void failingDesignRecoversOnTheFallbackModelWithTheFailureInItsPrompt() {
        WorkflowRun run = engine.run("e2e-fallback", ChangeType.GREENFIELD, "shorten urls", "design:2");

        assertThat(run.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(attempts.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(a -> a.getStageId().equals("design")).toList())
                .extracting(StageAttempt::getModel, StageAttempt::isSucceeded)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("claude-opus-5.5", false),
                        org.assertj.core.groups.Tuple.tuple("claude-opus-5.5", false),
                        org.assertj.core.groups.Tuple.tuple("claude-sonnet-5.5", true));
        AgentRequest designCall = ((MockAgentRuntime) runtime).requests().stream()
                .filter(r -> r.runId().equals(run.getId()) && r.stageId().equals("design"))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(designCall.model()).isEqualTo("claude-sonnet-5.5");
        assertThat(designCall.userPrompt())
                .contains("## Previous attempt failed\nchaos: injected failure on attempt 2");
    }

    @Test
    void permanentBuildFailureRollsTheBranchBackToTheApprovedDesignAndKeepsTheFailedState() {
        WorkflowRun run = engine.run("e2e-rollback", ChangeType.GREENFIELD, "shorten urls", "build-test:always");

        String tag = "agent-failed/" + run.getId().substring(0, 8);
        String designCommit = stage(run, "design").getCommitSha();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(run.getStopReason())
                .isEqualTo("stage build-test failed after 1 attempt(s): chaos: injected failure on attempt 1");
        assertThat(run.getRollbackTarget()).isEqualTo(designCommit);
        assertThat(run.getPreservedRef()).isEqualTo(tag);

        assertThat(TestProcesses.git(REPO, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(run.getBranch());
        assertThat(TestProcesses.git(REPO, "rev-parse", "HEAD")).isEqualTo(designCommit);
        assertThat(TestProcesses.git(REPO, "status", "--porcelain")).isEmpty();
        assertThat(REPO.resolve("url-shortener/src/main/java/com/veenaacharya/shortener/ShortCodeGenerator.java"))
                .doesNotExist();
        assertThat(TestProcesses.git(REPO, "log", "--format=%s", designCommit + ".." + tag).lines()
                .map(s -> s.replaceAll("^chore\\(agent\\): | output \\[run .*$", "")).toList())
                .containsExactlyInAnyOrder("code", "tests");
        assertThat(stage(run, "docs").getStatus()).isEqualTo(StageStatus.BLOCKED);
    }

    private StageExecution stage(WorkflowRun run, String stageId) {
        return stageExecutions.findByRunIdOrderByIdAsc(run.getId()).stream()
                .filter(s -> s.getStageId().equals(stageId)).findFirst().orElseThrow();
    }
}

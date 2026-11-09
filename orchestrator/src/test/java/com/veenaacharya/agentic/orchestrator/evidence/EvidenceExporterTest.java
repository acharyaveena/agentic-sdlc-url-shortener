package com.veenaacharya.agentic.orchestrator.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.agent.MockAgentRuntime;
import com.veenaacharya.agentic.orchestrator.audit.JsonLinesAuditLog;
import com.veenaacharya.agentic.orchestrator.audit.AuditProperties;
import com.veenaacharya.agentic.orchestrator.engine.EngineFixture;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.metrics.MetricsService;
import com.veenaacharya.agentic.orchestrator.recovery.RecoveryProperties;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.DecisionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageAttemptRepository;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowLoader;
import com.veenaacharya.agentic.orchestrator.workspace.WorkspaceRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class EvidenceExporterTest {

    private static final WorkflowDefinition WORKFLOW = new WorkflowLoader().loadFromClasspath("workflow.yml");

    @TempDir
    Path repo;

    @Autowired
    private WorkflowRunRepository runs;
    @Autowired
    private StageExecutionRepository stages;
    @Autowired
    private StageAttemptRepository attempts;
    @Autowired
    private ApprovalRepository approvals;
    @Autowired
    private DecisionRepository decisions;

    private JsonLinesAuditLog audit;
    private EvidenceExporter exporter;

    @BeforeEach
    void setUp() {
        audit = new JsonLinesAuditLog(new AuditProperties(repo.resolve("audit").toString()));
        exporter = new EvidenceExporter(WORKFLOW, runs, stages, approvals, decisions,
                new MetricsService(runs, stages, attempts, approvals), audit, new WorkspaceRoot(repo));
    }

    /** Model-backed stages use the mock agent; build and policy-scan stages are stood in for. */
    private StageExecutor executor() {
        AgentStageExecutor agent = new AgentStageExecutor(new MockAgentRuntime(), new AgentProperties("mock", null, null, 0));
        return context -> switch (context.stage().kind()) {
            case "build" -> StageResult.success("## Build result\n\nExit code: 0\n");
            case "policy-scan" -> StageResult.success("## Findings\nnone\n\n## Verdict\nPASS\n");
            default -> agent.execute(context);
        };
    }

    private WorkflowEngine engine() {
        return new EngineFixture(runs, stages, attempts, approvals, decisions)
                .executor(executor()).audit(audit).autoApprove(true)
                .recovery(new RecoveryProperties(1, Map.of())).engine();
    }

    private WorkflowRun finishedDemoRun(String request) {
        return engine().run("demo", ChangeType.GREENFIELD, request);
    }

    @Test
    void exportsRunSummaryNarrativeStagesApprovalsLineageMetricsAndAudit() throws IOException {
        WorkflowRun run = finishedDemoRun("Build a URL shortener");

        List<String> files = exporter.export(run.getId(), Path.of("scenarios/demo"));

        assertThat(files).containsExactly("request.md", "run.md", "artifacts/requirements.md", "artifacts/design.md",
                "artifacts/build-test.md", "artifacts/policy-scan.md", "artifacts/release-readiness.md",
                "approvals.md", "lineage.md", "metrics.md", "audit.jsonl");
        Path dir = repo.resolve("scenarios/demo");
        assertThat(Files.readString(dir.resolve("request.md"))).isEqualTo("# Change request\n\nBuild a URL shortener\n");
        assertThat(Files.readString(dir.resolve("run.md")))
                .contains("| run | " + run.getId() + " |", "| scenario | demo |", "| change type | greenfield |",
                        "| status | AWAITING_APPROVAL |", "| requirements | SUCCEEDED | 1 | 1 |",
                        "| impact-analysis | SKIPPED |", "| release-readiness | AWAITING_APPROVAL | 1 | 1 |");
        assertThat(Files.readString(dir.resolve("artifacts/requirements.md"))).contains("## Problem statement");
        assertThat(Files.readString(dir.resolve("approvals.md")))
                .contains("| requirements | requirements-signoff | APPROVED | auto |", "| design | design-and-schema | APPROVED | auto |",
                        "| release-readiness | release | PENDING | - |");
        assertThat(Files.readString(dir.resolve("lineage.md")))
                .contains("| requirements | 1 |", "| design | 1 |", "requirements=");
        assertThat(Files.readString(dir.resolve("metrics.md"))).startsWith("## Metrics (run " + run.getId() + ")");
        assertThat(Files.readString(dir.resolve("audit.jsonl"))).contains("\"event\":\"RUN_STARTED\"");
    }

    @Test
    void codeTestsAndDocsStagesAreNotCopiedBecauseTheirFilesLiveInTheRepository() {
        WorkflowRun run = finishedDemoRun("Build it");

        List<String> files = exporter.export(run.getId(), Path.of("scenarios/demo"));

        assertThat(files).noneMatch(f -> f.equals("artifacts/code.md") || f.equals("artifacts/tests.md")
                || f.equals("artifacts/docs.md"));
        assertThat(repo.resolve("scenarios/demo/artifacts/code.md")).doesNotExist();
    }

    @Test
    void safeStoppedRunShowsReasonAndRollbackInTheSummary() throws IOException {
        WorkflowRun run = new EngineFixture(runs, stages, attempts, approvals, decisions)
                .executor(executor()).audit(audit).autoApprove(true)
                .recovery(new RecoveryProperties(1, Map.of())).engine()
                .run("demo", ChangeType.GREENFIELD, "Build it", "build-test:always");

        exporter.export(run.getId(), Path.of("scenarios/chaos"));

        assertThat(Files.readString(repo.resolve("scenarios/chaos/run.md")))
                .contains("| status | SAFE_STOPPED |", "| chaos | build-test:always |",
                        "| stop reason | stage build-test failed after 1 attempt(s): chaos: injected failure on attempt 1 |",
                        "| build-test | FAILED | 1 | 1 |", "| docs | BLOCKED |");
    }

    @Test
    void multiLineStopReasonShowsItsFirstLineInTheTableAndTheFullTextInADetailBlock() throws IOException {
        StageExecutor failingBuild = context -> context.stage().kind().equals("build")
                ? StageResult.failure("build failed (exit 1):\n[ERROR] A.java:[7,32] package x does not exist\n[ERROR] more")
                : executor().execute(context);
        WorkflowRun run = new EngineFixture(runs, stages, attempts, approvals, decisions)
                .executor(failingBuild).audit(audit).autoApprove(true)
                .recovery(new RecoveryProperties(1, Map.of())).engine()
                .run("demo", ChangeType.GREENFIELD, "Build it");

        exporter.export(run.getId(), Path.of("scenarios/failed-build"));

        String summary = Files.readString(repo.resolve("scenarios/failed-build/run.md"));
        assertThat(summary).contains("| stop reason | stage build-test failed after 1 attempt(s): build failed (exit 1): |")
                .contains("## Stop reason detail", "[ERROR] A.java:[7,32] package x does not exist\n[ERROR] more");
        assertThat(summary.lines().filter(l -> l.startsWith("| stop reason")).count()).isEqualTo(1);
    }

    @Test
    void reExportOverwritesPreviousFiles() throws IOException {
        WorkflowRun run = finishedDemoRun("Build it");
        exporter.export(run.getId(), Path.of("scenarios/demo"));
        Files.writeString(repo.resolve("scenarios/demo/request.md"), "stale");

        exporter.export(run.getId(), Path.of("scenarios/demo"));

        assertThat(Files.readString(repo.resolve("scenarios/demo/request.md"))).contains("Build it");
    }

    @Test
    void contentWithLocalPathsOrPersonalDataIsRefusedAndNothingIsWritten() {
        WorkflowRun withPath = finishedDemoRun("Build it in C:\\Users\\someone\\proj");
        WorkflowRun withEmail = finishedDemoRun("Ask jane.doe@gmail.com");

        assertThatThrownBy(() -> exporter.export(withPath.getId(), Path.of("scenarios/a")))
                .isInstanceOf(EvidenceRejectedException.class)
                .hasMessageContaining("request.md: local filesystem path on line 3")
                .hasMessageNotContaining("someone");
        assertThatThrownBy(() -> exporter.export(withEmail.getId(), Path.of("scenarios/b")))
                .isInstanceOf(EvidenceRejectedException.class)
                .satisfies(e -> assertThat(((EvidenceRejectedException) e).problems())
                        .contains("request.md: email address on line 3"));
        assertThat(repo.resolve("scenarios")).doesNotExist();
    }

    @Test
    void onlyFoldersUnderScenariosAreAcceptedAsTargets() {
        WorkflowRun run = finishedDemoRun("Build it");

        for (String bad : List.of("docs/evidence", "scenarios", "scenarios/../orchestrator", "../outside", "x")) {
            assertThatThrownBy(() -> exporter.export(run.getId(), Path.of(bad))).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("evidence must go into a folder under scenarios/");
        }
        assertThatThrownBy(() -> exporter.export(run.getId(), repo.resolve("..").resolve("elsewhere").toAbsolutePath()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.resolve("orchestrator")).doesNotExist();
    }

    @Test
    void unknownRunIsReported() {
        assertThatThrownBy(() -> exporter.export("nope", Path.of("scenarios/x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown run: nope");
    }
}

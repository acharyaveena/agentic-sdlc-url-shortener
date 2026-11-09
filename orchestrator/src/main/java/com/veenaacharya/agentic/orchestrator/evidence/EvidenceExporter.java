package com.veenaacharya.agentic.orchestrator.evidence;

import com.veenaacharya.agentic.orchestrator.audit.AuditLog;
import com.veenaacharya.agentic.orchestrator.metrics.MetricsService;
import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.Decision;
import com.veenaacharya.agentic.orchestrator.state.DecisionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import com.veenaacharya.agentic.orchestrator.workspace.WorkspaceRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Copies a run's evidence into {@code scenarios/<name>/}: request, run summary, stage artifacts, approvals,
 * lineage, metrics and the audit log. Everything is scanned first and nothing is written if a problem is found.
 */
@Component
public class EvidenceExporter {

    public static final String SCENARIOS_DIR = "scenarios";
    private static final int SHORT = 10;

    private final WorkflowDefinition workflow;
    private final WorkflowRunRepository runs;
    private final StageExecutionRepository stages;
    private final ApprovalRepository approvals;
    private final DecisionRepository decisions;
    private final MetricsService metrics;
    private final AuditLog audit;
    private final Path root;
    private final EvidenceScanner scanner;

    public EvidenceExporter(WorkflowDefinition workflow, WorkflowRunRepository runs,
                            StageExecutionRepository stages, ApprovalRepository approvals,
                            DecisionRepository decisions, MetricsService metrics, AuditLog audit,
                            WorkspaceRoot root) {
        this.workflow = workflow;
        this.runs = runs;
        this.stages = stages;
        this.approvals = approvals;
        this.decisions = decisions;
        this.metrics = metrics;
        this.audit = audit;
        this.root = root.path();
        this.scanner = new EvidenceScanner(List.of(System.getProperty("user.home", ""), this.root.toString(),
                Path.of("").toAbsolutePath().toString()));
    }

    /** Writes the evidence and returns the files created, relative to {@code target}. */
    public List<String> export(String runId, Path target) {
        Path dir = target.isAbsolute() ? target.normalize() : root.resolve(target).normalize();
        if (!dir.startsWith(root.resolve(SCENARIOS_DIR).normalize()) || dir.equals(root.resolve(SCENARIOS_DIR))) {
            throw new IllegalArgumentException("evidence must go into a folder under " + SCENARIOS_DIR + "/, was: "
                    + target);
        }
        WorkflowRun run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
        Map<String, String> files = collect(run);
        List<String> problems = scanner.scan(files);
        if (!problems.isEmpty()) {
            throw new EvidenceRejectedException(problems);
        }
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path path = dir.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write evidence to " + dir, e);
        }
        return List.copyOf(files.keySet());
    }

    private Map<String, String> collect(WorkflowRun run) {
        List<StageExecution> executions = stages.findByRunIdOrderByIdAsc(run.getId());
        Map<String, String> files = new LinkedHashMap<>();
        files.put("request.md", "# Change request\n\n" + (run.getRequest() == null ? "" : run.getRequest()) + "\n");
        files.put("run.md", runSummary(run, executions));
        for (StageExecution stage : executions) {
            boolean reviewable = stage.getStatus() == StageStatus.SUCCEEDED
                    || stage.getStatus() == StageStatus.AWAITING_APPROVAL;
            // Code, tests and docs are already in the repository as files; only the narrative stages are copied.
            if (reviewable && stage.getOutput() != null && !workflow.stage(stage.getStageId()).kind().equals("files")) {
                files.put("artifacts/" + stage.getStageId() + ".md", stage.getOutput());
            }
        }
        files.put("approvals.md", approvalTable(approvals.findByRunIdOrderByIdAsc(run.getId())));
        files.put("lineage.md", lineageTable(decisions.findByRunIdOrderByIdAsc(run.getId())));
        files.put("metrics.md", metrics.forRun(run.getId()).toMarkdown());
        audit.fileFor(run.getId()).filter(Files::exists).ifPresent(file -> {
            try {
                files.put("audit.jsonl", Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read audit log " + file, e);
            }
        });
        return files;
    }

    private static String runSummary(WorkflowRun run, List<StageExecution> executions) {
        StringBuilder md = new StringBuilder("# Run summary\n\n")
                .append("| field | value |\n|---|---|\n")
                .append("| run | ").append(run.getId()).append(" |\n")
                .append("| scenario | ").append(run.getScenario()).append(" |\n")
                .append("| change type | ").append(run.getChangeType().name().toLowerCase(Locale.ROOT)).append(" |\n")
                .append("| status | ").append(run.getStatus()).append(" |\n")
                .append("| branch | ").append(orDash(run.getBranch())).append(" |\n");
        if (run.getChaos() != null) {
            md.append("| chaos | ").append(run.getChaos()).append(" |\n");
        }
        if (run.getStopReason() != null) {
            md.append("| stop reason | ").append(escape(firstLine(run.getStopReason()))).append(" |\n")
                    .append("| rolled back to | ").append(orDash(shortSha(run.getRollbackTarget()))).append(" |\n")
                    .append("| failed state kept at | ").append(orDash(run.getPreservedRef())).append(" |\n");
        }
        md.append("\n## Stages\n\n| stage | status | revision | attempts | commit |\n|---|---|---|---|---|\n");
        for (StageExecution stage : executions) {
            md.append("| ").append(stage.getStageId()).append(" | ").append(stage.getStatus()).append(" | ")
                    .append(stage.getRevision()).append(" | ").append(stage.getAttempt()).append(" | ")
                    .append(orDash(shortSha(stage.getCommitSha()))).append(" |\n");
        }
        if (run.getStopReason() != null && run.getStopReason().lines().count() > 1) {
            md.append("\n## Stop reason detail\n\n```text\n").append(run.getStopReason()).append("\n```\n");
        }
        return md.toString();
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse("");
    }

    private static String approvalTable(List<Approval> approvals) {
        StringBuilder md = new StringBuilder("# Approvals\n\n"
                + "| stage | kind | status | decided by | reason | requested | decided |\n|---|---|---|---|---|---|---|\n");
        for (Approval a : approvals) {
            md.append("| ").append(a.getStageId()).append(" | ").append(a.getKind()).append(" | ")
                    .append(a.getStatus()).append(" | ").append(orDash(a.getDecidedBy())).append(" | ")
                    .append(a.getReason() == null || a.getReason().isBlank() ? "-" : escape(a.getReason()))
                    .append(" | ").append(a.getRequestedAt()).append(" | ")
                    .append(a.getDecidedAt() == null ? "-" : a.getDecidedAt().toString()).append(" |\n");
        }
        return md.toString();
    }

    private static String lineageTable(List<Decision> decisions) {
        StringBuilder md = new StringBuilder("# Decision lineage\n\n"
                + "Hashes are the first " + SHORT + " characters of SHA-256.\n\n"
                + "| stage | revision | input | output | model | commit | built from |\n|---|---|---|---|---|---|---|\n");
        for (Decision d : decisions) {
            List<String> builtFrom = new ArrayList<>();
            if (d.getUpstreamHashes() != null && !d.getUpstreamHashes().isBlank()) {
                for (String entry : d.getUpstreamHashes().split(";")) {
                    int eq = entry.indexOf('=');
                    builtFrom.add(entry.substring(0, eq + 1) + shortSha(entry.substring(eq + 1)));
                }
            }
            md.append("| ").append(d.getStageId()).append(" | ").append(d.getRevision()).append(" | ")
                    .append(shortSha(d.getInputHash())).append(" | ").append(shortSha(d.getOutputHash())).append(" | ")
                    .append(orDash(d.getModel())).append(" | ").append(orDash(shortSha(d.getCommitSha()))).append(" | ")
                    .append(builtFrom.isEmpty() ? "-" : String.join(", ", builtFrom)).append(" |\n");
        }
        return md.toString();
    }

    private static String shortSha(String sha) {
        return sha == null ? null : sha.length() > SHORT ? sha.substring(0, SHORT) : sha;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String escape(String text) {
        return text.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}

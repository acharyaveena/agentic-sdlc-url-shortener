package com.veenaacharya.agentic.orchestrator.cli;

import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.audit.AuditLog;
import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.metrics.MetricsService;
import com.veenaacharya.agentic.orchestrator.replan.ReplanService;
import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalStatus;
import com.veenaacharya.agentic.orchestrator.state.Decision;
import com.veenaacharya.agentic.orchestrator.state.DecisionRepository;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageAttempt;
import com.veenaacharya.agentic.orchestrator.state.StageAttemptRepository;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Command-line entry; see {@link #USAGE}. */
@Component
public class OrchestratorCli implements ApplicationRunner {

    static final String USAGE = String.join(System.lineSeparator(),
            "Usage:",
            "  --scenario=<name> [--change-type=greenfield|brownfield] [--request=<text>]   start a run",
            "      [--chaos=<stage>:<count|always>[,...]]                                    inject failures to demo recovery",
            "  --approve=<run>:<stage> [--actor=<name>] [--reason=<text>]                    approve, then resume",
            "  --reject=<run>:<stage> --reason=<text> [--actor=<name>] [--replan]            reject; --replan redoes the stage",
            "  --replan=<run>:<stage> --reason=<text> [--actor=<name>]                       redo a stage; changed outputs make",
            "                                                                                downstream stages STALE",
            "  --resume=<run>                                                                continue a paused run",
            "  --status=<run>                                                                show stages and approvals",
            "  --lineage=<run>                                                               show output hashes per revision",
            "  --metrics[=<run>]                                                             reliability metrics",
            "  --stage=<id> --request=<text>                                                 preview one stage");

    static final String DEFAULT_ACTOR = "reviewer";

    private final WorkflowEngine engine;
    private final AgentStageExecutor preview;
    private final WorkflowDefinition workflow;
    private final WorkflowRunRepository runs;
    private final StageExecutionRepository stageExecutions;
    private final StageAttemptRepository attempts;
    private final ApprovalService approvals;
    private final Collaborators more;
    private final ArtifactWriter artifacts = new ArtifactWriter();
    private final PrintStream out;

    /** Services for re-planning and reporting, grouped to keep the constructor readable. */
    public record Collaborators(ReplanService replans, MetricsService metrics, DecisionRepository decisions,
                                AuditLog audit) {
    }

    @Autowired
    public OrchestratorCli(WorkflowEngine engine, AgentStageExecutor preview, WorkflowDefinition workflow,
                           WorkflowRunRepository runs, StageExecutionRepository stageExecutions,
                           StageAttemptRepository attempts, ApprovalService approvals, ReplanService replans,
                           MetricsService metrics, DecisionRepository decisions, AuditLog audit) {
        this(engine, preview, workflow, runs, stageExecutions, attempts, approvals,
                new Collaborators(replans, metrics, decisions, audit), System.out);
    }

    OrchestratorCli(WorkflowEngine engine, AgentStageExecutor preview, WorkflowDefinition workflow,
                    WorkflowRunRepository runs, StageExecutionRepository stageExecutions,
                    StageAttemptRepository attempts, ApprovalService approvals, Collaborators more,
                    PrintStream out) {
        this.engine = engine;
        this.preview = preview;
        this.workflow = workflow;
        this.runs = runs;
        this.stageExecutions = stageExecutions;
        this.attempts = attempts;
        this.approvals = approvals;
        this.more = more;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("stage")) {
            previewStage(args);
        } else if (args.containsOption("scenario")) {
            startRun(args);
        } else if (args.containsOption("approve")) {
            decide(args, true);
        } else if (args.containsOption("reject")) {
            decide(args, false);
        } else if (args.containsOption("replan")) {
            replan(args);
        } else if (args.containsOption("resume")) {
            report(engine.resume(option(args, "resume", "")));
        } else if (args.containsOption("status")) {
            report(runs.findById(option(args, "status", ""))
                    .orElseThrow(() -> new IllegalArgumentException("unknown run: " + option(args, "status", ""))));
        } else if (args.containsOption("lineage")) {
            lineage(option(args, "lineage", ""));
        } else if (args.containsOption("metrics")) {
            String runId = option(args, "metrics", "");
            out.print((runId.isBlank() ? more.metrics().forAllRuns() : more.metrics().forRun(runId)).toMarkdown());
        } else {
            out.println(USAGE);
        }
    }

    private void startRun(ApplicationArguments args) {
        ChangeType changeType = parseChangeType(option(args, "change-type", "brownfield"));
        String chaos = option(args, "chaos", "");
        if (!chaos.isBlank()) {
            out.println("CHAOS MODE: injecting failures (" + chaos + ") to demonstrate recovery");
        }
        report(engine.run(option(args, "scenario", ""), changeType, option(args, "request", ""), chaos));
    }

    private void decide(ApplicationArguments args, boolean approve) {
        String[] target = parseTarget(option(args, approve ? "approve" : "reject", ""));
        String actor = option(args, "actor", DEFAULT_ACTOR);
        String reason = option(args, "reason", "");
        if (approve) {
            Approval approval = approvals.approve(target[0], target[1], actor, reason);
            out.printf("Approved %s (%s) as %s%n", approval.getStageId(), approval.getKind(), actor);
        } else {
            if (reason.isBlank()) {
                throw new IllegalArgumentException("--reject needs --reason=<text>");
            }
            Approval approval = approvals.reject(target[0], target[1], actor, reason);
            out.printf("Rejected %s (%s) as %s%n", approval.getStageId(), approval.getKind(), actor);
            if (args.containsOption("replan")) {
                out.printf("Re-planning %s with the rejection reason as feedback%n", target[1]);
                report(more.replans().replan(target[0], target[1], actor, reason));
                return;
            }
        }
        report(engine.resume(target[0]));
    }

    private void replan(ApplicationArguments args) {
        String[] target = parseTarget(option(args, "replan", ""));
        String reason = option(args, "reason", "");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("--replan needs --reason=<text>");
        }
        out.printf("Re-planning %s%n", target[1]);
        report(more.replans().replan(target[0], target[1], option(args, "actor", DEFAULT_ACTOR), reason));
    }

    private void lineage(String runId) {
        runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
        out.printf("Lineage of run %s%n", runId);
        out.printf("  %-18s %-4s %-10s %-10s %-18s %s%n", "stage", "rev", "input", "output", "model", "built from");
        for (Decision decision : more.decisions().findByRunIdOrderByIdAsc(runId)) {
            String builtFrom = decision.getUpstreamHashes() == null || decision.getUpstreamHashes().isEmpty()
                    ? "-"
                    : Arrays.stream(decision.getUpstreamHashes().split(";"))
                    .map(e -> e.substring(0, e.indexOf('=') + 1) + shortSha(e.substring(e.indexOf('=') + 1)))
                    .collect(Collectors.joining(", "));
            out.printf("  %-18s %-4d %-10s %-10s %-18s %s%n", decision.getStageId(), decision.getRevision(),
                    shortSha(decision.getInputHash()), shortSha(decision.getOutputHash()),
                    decision.getModel() == null ? "-" : decision.getModel(), builtFrom);
        }
    }

    private void report(WorkflowRun run) {
        List<StageExecution> stages = stageExecutions.findByRunIdOrderByIdAsc(run.getId());
        List<StageAttempt> tries = attempts.findByRunIdOrderByIdAsc(run.getId());
        out.printf("Run %s (%s, %s): %s%n", run.getId(), run.getScenario(),
                run.getChangeType().name().toLowerCase(Locale.ROOT), run.getStatus());
        for (StageExecution stage : stages) {
            String notes = (stage.getRevision() > 1 ? " rev " + stage.getRevision() : "")
                    + (stage.getAttempt() > 1 ? " (" + stage.getAttempt() + " attempts)" : "");
            out.printf("  %-18s %s%s%n", stage.getStageId(), stage.getStatus(), notes);
            if (stage.getAttempt() > 1) {
                tries.stream()
                        .filter(t -> t.getStageId().equals(stage.getStageId()) && t.getRevision() == stage.getRevision())
                        .forEach(t -> out.printf("      attempt %d %-18s %s%n", t.getAttemptNumber(),
                                t.getModel() == null ? "-" : t.getModel(),
                                t.isSucceeded() ? "ok" : "failed: " + firstLine(t.getError())));
            }
        }
        Path dir = Path.of("runs", run.getId());
        if (!artifacts.write(dir, stages).isEmpty()) {
            out.println("Stage outputs written to " + dir);
        }
        for (Approval approval : approvals.forRun(run.getId())) {
            if (approval.getStatus() == ApprovalStatus.PENDING) {
                out.printf("Awaiting approval: %s (%s). Review %s, then run:%n  --approve=%s:%s --actor=<you>%n"
                                + "  or --reject=%s:%s --reason=<why> --actor=<you> [--replan]%n",
                        approval.getStageId(), approval.getKind(),
                        dir.resolve(approval.getStageId() + ".md"),
                        run.getId(), approval.getStageId(), run.getId(), approval.getStageId());
            }
        }
        stages.stream().filter(s -> s.getStatus() == StageStatus.FAILED || s.getStatus() == StageStatus.BLOCKED
                        || s.getStatus() == StageStatus.STALE)
                .forEach(s -> out.printf("  %s: %s%n", s.getStageId(), s.getError()));
        if (run.getStatus() == RunStatus.SAFE_STOPPED) {
            out.println("Safe-stopped: " + run.getStopReason());
            if (run.getRollbackTarget() != null) {
                out.printf("Rolled back %s to %s; the failed state is kept at tag %s%n", run.getBranch(),
                        shortSha(run.getRollbackTarget()), run.getPreservedRef());
            }
        }
        more.audit().fileFor(run.getId()).ifPresent(file -> out.println("Audit log: " + file));
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.lines().findFirst().orElse("");
    }

    private static String shortSha(String sha) {
        if (sha == null) {
            return "-";
        }
        return sha.length() > 10 ? sha.substring(0, 10) : sha;
    }

    private void previewStage(ApplicationArguments args) {
        String stageId = option(args, "stage", "");
        ChangeType changeType = parseChangeType(option(args, "change-type", "greenfield"));
        StageContext context = new StageContext("preview", workflow.stage(stageId), changeType,
                option(args, "request", ""), Map.of());

        StageResult result = preview.execute(context);

        out.println(result.succeeded() ? result.output() : "Stage failed: " + result.error());
    }

    private static String[] parseTarget(String value) {
        String[] parts = value.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("expected <run-id>:<stage-id>, was: " + value);
        }
        return parts;
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static ChangeType parseChangeType(String value) {
        try {
            return ChangeType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--change-type must be greenfield or brownfield, was: " + value, e);
        }
    }
}

package com.veenaacharya.agentic.orchestrator.cli;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.gate.ApprovalService;
import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalStatus;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            "  --approve=<run>:<stage> [--actor=<name>] [--reason=<text>]                    approve, then resume",
            "  --reject=<run>:<stage> --reason=<text> [--actor=<name>]                       reject, then resume",
            "  --resume=<run>                                                                continue a paused run",
            "  --status=<run>                                                                show stages and approvals",
            "  --stage=<id> --request=<text>                                                 preview one stage");

    static final String DEFAULT_ACTOR = "reviewer";

    private final WorkflowEngine engine;
    private final StageExecutor executor;
    private final WorkflowDefinition workflow;
    private final WorkflowRunRepository runs;
    private final StageExecutionRepository stageExecutions;
    private final ApprovalService approvals;
    private final ArtifactWriter artifacts = new ArtifactWriter();
    private final PrintStream out;

    @Autowired
    public OrchestratorCli(WorkflowEngine engine, StageExecutor executor, WorkflowDefinition workflow,
                           WorkflowRunRepository runs, StageExecutionRepository stageExecutions,
                           ApprovalService approvals) {
        this(engine, executor, workflow, runs, stageExecutions, approvals, System.out);
    }

    OrchestratorCli(WorkflowEngine engine, StageExecutor executor, WorkflowDefinition workflow,
                    WorkflowRunRepository runs, StageExecutionRepository stageExecutions,
                    ApprovalService approvals, PrintStream out) {
        this.engine = engine;
        this.executor = executor;
        this.workflow = workflow;
        this.runs = runs;
        this.stageExecutions = stageExecutions;
        this.approvals = approvals;
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
        } else if (args.containsOption("resume")) {
            report(engine.resume(option(args, "resume", "")));
        } else if (args.containsOption("status")) {
            report(runs.findById(option(args, "status", ""))
                    .orElseThrow(() -> new IllegalArgumentException("unknown run: " + option(args, "status", ""))));
        } else {
            out.println(USAGE);
        }
    }

    private void startRun(ApplicationArguments args) {
        ChangeType changeType = parseChangeType(option(args, "change-type", "brownfield"));
        report(engine.run(option(args, "scenario", ""), changeType, option(args, "request", "")));
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
        }
        report(engine.resume(target[0]));
    }

    private void report(WorkflowRun run) {
        List<StageExecution> stages = stageExecutions.findByRunIdOrderByIdAsc(run.getId());
        out.printf("Run %s (%s, %s): %s%n", run.getId(), run.getScenario(),
                run.getChangeType().name().toLowerCase(Locale.ROOT), run.getStatus());
        for (StageExecution stage : stages) {
            out.printf("  %-18s %s%n", stage.getStageId(), stage.getStatus());
        }
        Path dir = Path.of("runs", run.getId());
        if (!artifacts.write(dir, stages).isEmpty()) {
            out.println("Stage outputs written to " + dir);
        }
        for (Approval approval : approvals.forRun(run.getId())) {
            if (approval.getStatus() == ApprovalStatus.PENDING) {
                out.printf("Awaiting approval: %s (%s). Review %s, then run:%n  --approve=%s:%s --actor=<you>%n"
                                + "  or --reject=%s:%s --reason=<why> --actor=<you>%n",
                        approval.getStageId(), approval.getKind(),
                        dir.resolve(approval.getStageId() + ".md"),
                        run.getId(), approval.getStageId(), run.getId(), approval.getStageId());
            }
        }
        stages.stream().filter(s -> s.getStatus() == StageStatus.FAILED || s.getStatus() == StageStatus.BLOCKED)
                .forEach(s -> out.printf("  %s: %s%n", s.getStageId(), s.getError()));
    }

    private void previewStage(ApplicationArguments args) {
        String stageId = option(args, "stage", "");
        ChangeType changeType = parseChangeType(option(args, "change-type", "greenfield"));
        StageContext context = new StageContext("preview", workflow.stage(stageId), changeType,
                option(args, "request", ""), Map.of());

        StageResult result = executor.execute(context);

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

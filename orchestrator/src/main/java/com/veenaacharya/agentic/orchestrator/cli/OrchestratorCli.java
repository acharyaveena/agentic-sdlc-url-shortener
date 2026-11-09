package com.veenaacharya.agentic.orchestrator.cli;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
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

/**
 * Command-line entry. Full run: {@code --scenario=<name> [--change-type=greenfield|brownfield] [--request=<text>]}.
 * Single-stage preview (no persistence, no upstream input): {@code --stage=<id> --request=<text>}.
 */
@Component
public class OrchestratorCli implements ApplicationRunner {

    static final String USAGE =
            "Usage: --scenario=<name> [--change-type=greenfield|brownfield] [--request=<text>]"
                    + System.lineSeparator()
                    + "       --stage=<id> --request=<text>   (preview one stage)";

    private final WorkflowEngine engine;
    private final StageExecutor executor;
    private final WorkflowDefinition workflow;
    private final StageExecutionRepository stageExecutions;
    private final ArtifactWriter artifacts = new ArtifactWriter();
    private final PrintStream out;

    @Autowired
    public OrchestratorCli(WorkflowEngine engine, StageExecutor executor, WorkflowDefinition workflow,
                           StageExecutionRepository stageExecutions) {
        this(engine, executor, workflow, stageExecutions, System.out);
    }

    OrchestratorCli(WorkflowEngine engine, StageExecutor executor, WorkflowDefinition workflow,
                    StageExecutionRepository stageExecutions, PrintStream out) {
        this.engine = engine;
        this.executor = executor;
        this.workflow = workflow;
        this.stageExecutions = stageExecutions;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("stage")) {
            previewStage(args);
        } else if (args.containsOption("scenario")) {
            runScenario(args);
        } else {
            out.println(USAGE);
        }
    }

    private void runScenario(ApplicationArguments args) {
        String scenario = option(args, "scenario", "");
        ChangeType changeType = parseChangeType(option(args, "change-type", "brownfield"));
        String request = option(args, "request", "");

        WorkflowRun run = engine.run(scenario, changeType, request);
        List<StageExecution> stages = stageExecutions.findByRunIdOrderByIdAsc(run.getId());

        out.printf("Run %s (%s, %s) finished: %s%n", run.getId(), scenario,
                changeType.name().toLowerCase(Locale.ROOT), run.getStatus());
        for (StageExecution stage : stages) {
            out.printf("  %-18s %s%n", stage.getStageId(), stage.getStatus());
        }
        Path dir = Path.of("runs", run.getId());
        if (!artifacts.write(dir, stages).isEmpty()) {
            out.println("Stage outputs written to " + dir);
        }
    }

    private void previewStage(ApplicationArguments args) {
        String stageId = option(args, "stage", "");
        ChangeType changeType = parseChangeType(option(args, "change-type", "greenfield"));
        StageContext context = new StageContext("preview", workflow.stage(stageId), changeType,
                option(args, "request", ""), Map.of());

        StageResult result = executor.execute(context);

        out.println(result.succeeded() ? result.output() : "Stage failed: " + result.error());
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

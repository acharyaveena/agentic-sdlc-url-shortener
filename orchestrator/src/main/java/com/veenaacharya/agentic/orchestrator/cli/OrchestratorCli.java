package com.veenaacharya.agentic.orchestrator.cli;

import com.veenaacharya.agentic.orchestrator.engine.WorkflowEngine;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Command-line entry: {@code --scenario=<name> [--change-type=greenfield|brownfield] [--request=<text>]}. */
@Component
public class OrchestratorCli implements ApplicationRunner {

    static final String USAGE =
            "Usage: --scenario=<name> [--change-type=greenfield|brownfield] [--request=<text>]";

    private final WorkflowEngine engine;
    private final StageExecutionRepository stageExecutions;
    private final PrintStream out;

    @Autowired
    public OrchestratorCli(WorkflowEngine engine, StageExecutionRepository stageExecutions) {
        this(engine, stageExecutions, System.out);
    }

    OrchestratorCli(WorkflowEngine engine, StageExecutionRepository stageExecutions, PrintStream out) {
        this.engine = engine;
        this.stageExecutions = stageExecutions;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("scenario")) {
            out.println(USAGE);
            return;
        }
        String scenario = option(args, "scenario", "");
        ChangeType changeType = parseChangeType(option(args, "change-type", "brownfield"));
        String request = option(args, "request", "");

        WorkflowRun run = engine.run(scenario, changeType, request);

        out.printf("Run %s (%s, %s) finished: %s%n", run.getId(), scenario,
                changeType.name().toLowerCase(Locale.ROOT), run.getStatus());
        for (StageExecution stage : stageExecutions.findByRunIdOrderByIdAsc(run.getId())) {
            out.printf("  %-18s %s%n", stage.getStageId(), stage.getStatus());
        }
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

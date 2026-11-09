package com.veenaacharya.agentic.orchestrator.workspace;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.policy.WritePathRule;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds and tests the product module for real; the stage fails if the build does. */
@Component
public class BuildStageExecutor implements StageExecutor {

    private static final int TAIL_LINES = 40;

    private final Path root;
    private final WorkspaceProperties properties;

    public BuildStageExecutor(WorkspaceRoot root, WorkspaceProperties properties) {
        this.root = root.path();
        this.properties = properties;
    }

    @Override
    public StageResult execute(StageContext context) {
        List<String> command = properties.buildCommand().isEmpty() ? defaultCommand() : properties.buildCommand();
        ProcessRunner.Result result = ProcessRunner.run(root,
                Duration.ofSeconds(properties.buildTimeoutSeconds()), command);
        String tail = sanitise(tail(result.output()));
        if (result.timedOut()) {
            return StageResult.failure("build timed out after " + properties.buildTimeoutSeconds() + "s:\n" + tail);
        }
        if (!result.ok()) {
            return StageResult.failure("build failed (exit " + result.exitCode() + "):\n" + errorLinesOr(tail));
        }
        return StageResult.success("## Build result\n\nCommand: `" + sanitise(String.join(" ", command))
                + "`\n\nExit code: 0\n\n```text\n" + tail + "\n```\n");
    }

    private List<String> defaultCommand() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = new ArrayList<>();
        command.add(root.resolve(windows ? "mvnw.cmd" : "mvnw").toString());
        command.addAll(List.of("-B", "-ntp", "-pl", WritePathRule.PRODUCT_DIR, "verify"));
        return command;
    }

    private static String tail(String output) {
        List<String> lines = output.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()));
    }

    /** Maven's [ERROR] lines say what went wrong; the surrounding [INFO] lines are noise, so they lead on failure. */
    private static String errorLinesOr(String tail) {
        String errors = tail.lines().filter(line -> line.startsWith("[ERROR]")).collect(Collectors.joining("\n"));
        return errors.isBlank() ? tail : errors;
    }

    /** Keeps local filesystem paths out of stored output. */
    private String sanitise(String text) {
        String forward = root.toString().replace('\\', '/');
        return text.replace(root.toString(), "<repo>").replace(forward, "<repo>");
    }
}

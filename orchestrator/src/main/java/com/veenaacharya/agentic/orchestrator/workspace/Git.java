package com.veenaacharya.agentic.orchestrator.workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Thin wrapper over the git command line, scoped to one repository. */
public class Git {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Path root;

    public Git(Path root) {
        this.root = root;
    }

    /** Runs git and returns its output; a non-zero exit throws with the subcommand and git's message. */
    public String run(String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessRunner.Result result = ProcessRunner.run(root, TIMEOUT, command);
        if (!result.ok()) {
            throw new IllegalStateException("git " + subcommand(args) + " failed (exit " + result.exitCode() + "): "
                    + result.output().strip());
        }
        return result.output();
    }

    public String head() {
        return run("rev-parse", "HEAD").strip();
    }

    public String currentBranch() {
        return run("rev-parse", "--abbrev-ref", "HEAD").strip();
    }

    public void requireClean() {
        String status = run("status", "--porcelain").strip();
        if (!status.isEmpty()) {
            throw new IllegalStateException("the working tree has uncommitted changes; commit or stash them "
                    + "before an agent run switches branches:\n" + status);
        }
    }

    private static String subcommand(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-c")) {
                i++;
            } else {
                return args[i];
            }
        }
        return "";
    }
}

package com.veenaacharya.agentic.orchestrator.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs an external command to completion with a time limit, capturing combined output. */
public final class ProcessRunner {

    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    private ProcessRunner() {
    }

    public record Result(int exitCode, String output, boolean timedOut) {

        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    public static Result run(Path dir, Duration timeout, List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start '" + command.get(0) + "': " + e.getMessage(), e);
        }
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), output));
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            reader.join(5_000);
            synchronized (output) {
                return new Result(finished ? process.exitValue() : -1, output.toString(), !finished);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running '" + command.get(0) + "'", e);
        }
    }

    private static void drain(InputStream in, StringBuilder output) {
        try (in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                synchronized (output) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException ignored) {
            // The process was destroyed; whatever was captured so far is the result.
        }
    }
}

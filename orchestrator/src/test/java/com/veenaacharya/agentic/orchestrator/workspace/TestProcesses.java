package com.veenaacharya.agentic.orchestrator.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Helpers that launch tiny JVM programs and git, so process tests behave the same on every operating system. */
public final class TestProcesses {

    private TestProcesses() {
    }

    /** Runs git in {@code dir} with a fixed identity, and fails the test if git fails. */
    public static String git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=tester",
                "-c", "user.email=tester@example.com", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        ProcessRunner.Result result = ProcessRunner.run(dir, Duration.ofSeconds(60), command);
        if (!result.ok()) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + result.output());
        }
        return result.output().strip();
    }

    /** A repository with one commit on {@code main}. */
    public static void initRepo(Path dir) throws IOException {
        git(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("README.md"), "hello\n");
        git(dir, "add", "README.md");
        git(dir, "commit", "-q", "-m", "initial");
    }

    public static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    public static List<String> run(Class<?> mainClass, String... args) {
        String classes;
        try {
            classes = Path.of(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        List<String> command = new java.util.ArrayList<>(List.of(java(), "-cp", classes, mainClass.getName()));
        command.addAll(List.of(args));
        return command;
    }

    /** Prints its arguments, then exits with the code given as the first argument. */
    public static final class Echo {

        public static void main(String[] args) {
            System.out.println(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            System.exit(Integer.parseInt(args[0]));
        }
    }

    /** Prints {@code line1} to {@code lineN}, where N is the first argument. */
    public static final class Lines {

        public static void main(String[] args) {
            for (int i = 1; i <= Integer.parseInt(args[0]); i++) {
                System.out.println("line" + i);
            }
        }
    }

    public static final class Sleep {

        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(60_000);
        }
    }
}

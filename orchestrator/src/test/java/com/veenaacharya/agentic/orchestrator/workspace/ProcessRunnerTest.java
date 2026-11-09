package com.veenaacharya.agentic.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {

    @TempDir
    Path tmp;

    @Test
    void capturesOutputAndZeroExit() {
        ProcessRunner.Result result = ProcessRunner.run(tmp, Duration.ofSeconds(30),
                TestProcesses.run(TestProcesses.Echo.class, "0", "hello", "world"));

        assertThat(result.ok()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output().strip()).isEqualTo("hello world");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void reportsNonZeroExitCode() {
        ProcessRunner.Result result = ProcessRunner.run(tmp, Duration.ofSeconds(30),
                TestProcesses.run(TestProcesses.Echo.class, "3", "boom"));

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output().strip()).isEqualTo("boom");
    }

    @Test
    void killsProcessThatExceedsTimeout() {
        long start = System.nanoTime();

        ProcessRunner.Result result = ProcessRunner.run(tmp, Duration.ofSeconds(1),
                TestProcesses.run(TestProcesses.Sleep.class));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void unknownProgramIsReported() {
        assertThatThrownBy(() -> ProcessRunner.run(tmp, Duration.ofSeconds(5), List.of("no-such-program-xyz")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("cannot start 'no-such-program-xyz'");
    }
}

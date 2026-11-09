package com.veenaacharya.agentic.orchestrator.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactWriterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tmp;

    @Test
    void writesOnlySucceededStagesAsMarkdownFiles() throws IOException {
        StageExecution ok = new StageExecution("r", "design");
        ok.start(NOW);
        ok.succeed("# plan", NOW);
        StageExecution failed = new StageExecution("r", "code");
        failed.start(NOW);
        failed.fail("boom", NOW);
        StageExecution skipped = new StageExecution("r", "impact-analysis");
        skipped.skip(NOW);

        List<Path> written = new ArtifactWriter().write(tmp.resolve("run-1"), List.of(ok, failed, skipped));

        assertThat(written).containsExactly(tmp.resolve("run-1").resolve("design.md"));
        assertThat(Files.readString(written.get(0))).isEqualTo("# plan");
        assertThat(tmp.resolve("run-1").resolve("code.md")).doesNotExist();
    }

    @Test
    void stageAwaitingApprovalIsWrittenSoAReviewerCanReadIt() throws IOException {
        StageExecution waiting = new StageExecution("r", "requirements");
        waiting.start(NOW);
        waiting.awaitApproval("# for review");

        List<Path> written = new ArtifactWriter().write(tmp.resolve("run-2"), List.of(waiting));

        assertThat(Files.readString(written.get(0))).isEqualTo("# for review");
    }

    @Test
    void emptyRunWritesNothing() {
        assertThat(new ArtifactWriter().write(tmp.resolve("none"), List.of())).isEmpty();
    }
}

package com.veenaacharya.agentic.orchestrator.cli;

import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes each succeeded stage's output to {@code <dir>/<stage>.md} so a human can review it. */
public class ArtifactWriter {

    public List<Path> write(Path dir, List<StageExecution> stages) {
        try {
            Files.createDirectories(dir);
            List<Path> written = new ArrayList<>();
            for (StageExecution stage : stages) {
                if (stage.getStatus() == StageStatus.SUCCEEDED && stage.getOutput() != null) {
                    Path file = dir.resolve(stage.getStageId() + ".md");
                    Files.writeString(file, stage.getOutput(), StandardCharsets.UTF_8);
                    written.add(file);
                }
            }
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write artifacts to " + dir, e);
        }
    }
}

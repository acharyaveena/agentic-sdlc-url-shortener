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

/** Writes stage outputs a human can review (succeeded or awaiting approval) to {@code <dir>/<stage>.md}. */
public class ArtifactWriter {

    public List<Path> write(Path dir, List<StageExecution> stages) {
        try {
            List<Path> written = new ArrayList<>();
            for (StageExecution stage : stages) {
                boolean reviewable = stage.getStatus() == StageStatus.SUCCEEDED
                        || stage.getStatus() == StageStatus.AWAITING_APPROVAL;
                if (reviewable && stage.getOutput() != null) {
                    Files.createDirectories(dir);
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

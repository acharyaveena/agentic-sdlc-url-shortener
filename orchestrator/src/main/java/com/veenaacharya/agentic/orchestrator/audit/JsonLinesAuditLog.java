package com.veenaacharya.agentic.orchestrator.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Appends each event as one JSON object per line to {@code <dir>/<run id>.jsonl}. */
@Component
@EnableConfigurationProperties(AuditProperties.class)
public class JsonLinesAuditLog implements AuditLog {

    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonLinesAuditLog(AuditProperties properties) {
        this.dir = Path.of(properties.dir()).toAbsolutePath().normalize();
    }

    @Override
    public synchronized void record(AuditEvent event) {
        Path file = fileFor(event.runId()).orElseThrow();
        try {
            Files.createDirectories(dir);
            Files.writeString(file, mapper.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise audit event " + event.event(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write audit log " + file, e);
        }
    }

    @Override
    public Optional<Path> fileFor(String runId) {
        // Run ids become file names, so anything that could escape the directory is refused.
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("invalid run id for audit log: " + runId);
        }
        return Optional.of(dir.resolve(runId + ".jsonl"));
    }
}

package com.veenaacharya.agentic.orchestrator.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonLinesAuditLogTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final List<String> KEYS = List.of("timestamp", "runId", "event", "stage", "revision", "attempt",
            "model", "inputHash", "outputHash", "decision", "actor", "detail");

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void appendsOneJsonObjectPerLineWithEveryKeyPresent() throws IOException {
        JsonLinesAuditLog log = new JsonLinesAuditLog(new AuditProperties(dir.toString()));

        log.record(AuditEvent.of("RUN_STARTED", "run-1").actor("orchestrator").at(NOW));
        log.record(AuditEvent.of("ATTEMPT_FAILED", "run-1").stage("design", 2).attempt(1, "claude-opus-5.5")
                .inputHash("in").outputHash("out").decision("FAILED").actor("agent").detail("timed out").at(NOW));

        List<String> lines = Files.readAllLines(dir.resolve("run-1.jsonl"));
        assertThat(lines).hasSize(2);
        JsonNode first = mapper.readTree(lines.get(0));
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(KEYS);
        assertThat(first.get("stage").isNull()).isTrue();
        assertThat(first.get("timestamp").asText()).isEqualTo("2026-01-01T00:00:00Z");
        JsonNode second = mapper.readTree(lines.get(1));
        assertThat(second.get("event").asText()).isEqualTo("ATTEMPT_FAILED");
        assertThat(second.get("stage").asText()).isEqualTo("design");
        assertThat(second.get("revision").asInt()).isEqualTo(2);
        assertThat(second.get("attempt").asInt()).isEqualTo(1);
        assertThat(second.get("model").asText()).isEqualTo("claude-opus-5.5");
        assertThat(second.get("inputHash").asText()).isEqualTo("in");
        assertThat(second.get("outputHash").asText()).isEqualTo("out");
        assertThat(second.get("decision").asText()).isEqualTo("FAILED");
        assertThat(second.get("actor").asText()).isEqualTo("agent");
        assertThat(second.get("detail").asText()).isEqualTo("timed out");
    }

    @Test
    void eachRunGetsItsOwnFileAndTheDirectoryIsCreated() {
        Path nested = dir.resolve("audit");
        JsonLinesAuditLog log = new JsonLinesAuditLog(new AuditProperties(nested.toString()));

        log.record(AuditEvent.of("RUN_STARTED", "run-a").at(NOW));
        log.record(AuditEvent.of("RUN_STARTED", "run-b").at(NOW));

        assertThat(nested.resolve("run-a.jsonl")).exists();
        assertThat(nested.resolve("run-b.jsonl")).exists();
        assertThat(log.fileFor("run-a")).contains(nested.toAbsolutePath().normalize().resolve("run-a.jsonl"));
    }

    @Test
    void runIdsThatCouldEscapeTheDirectoryAreRefused() {
        JsonLinesAuditLog log = new JsonLinesAuditLog(new AuditProperties(dir.toString()));

        for (String bad : new String[] {"../evil", "a/b", "a\\b", "", "x".repeat(65)}) {
            assertThatThrownBy(() -> log.fileFor(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("invalid run id for audit log");
        }
        assertThatThrownBy(() -> log.record(AuditEvent.of("X", null).at(NOW)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsAndNoOpLog() {
        assertThat(new AuditProperties(null).dir()).isEqualTo("runs/audit");
        AuditLog.NONE.record(AuditEvent.of("X", "run-1").at(NOW));
        assertThat(AuditLog.NONE.fileFor("run-1")).isEmpty();
    }
}

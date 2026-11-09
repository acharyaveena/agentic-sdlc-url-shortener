package com.veenaacharya.agentic.orchestrator.workspace;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.policy.FileChange;
import com.veenaacharya.agentic.orchestrator.policy.PolicyContext;
import com.veenaacharya.agentic.orchestrator.policy.PolicyGuard;
import com.veenaacharya.agentic.orchestrator.policy.Violation;
import com.veenaacharya.agentic.orchestrator.policy.WritePathRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Re-checks the whole product tree against the state rules: the detective backstop behind write-time checks. */
@Component
public class PolicyScanStageExecutor implements StageExecutor {

    private static final Set<String> TEXT_EXTENSIONS =
            Set.of("java", "xml", "yml", "yaml", "properties", "md", "sql", "txt", "json");
    private static final Set<String> SKIPPED_DIRS = Set.of("target", ".git");
    private static final long MAX_FILE_BYTES = 1_000_000;

    private final Path root;
    private final PolicyGuard guard;

    public PolicyScanStageExecutor(WorkspaceRoot root, @Qualifier("scanGuard") PolicyGuard guard) {
        this.root = root.path();
        this.guard = guard;
    }

    @Override
    public StageResult execute(StageContext context) {
        List<FileChange> files = readProductFiles();
        List<Violation> violations = guard.check(files, new PolicyContext(context.stage().id(), context.upstreamOutputs()));

        StringBuilder report = new StringBuilder("## Findings\n\n| rule | status | evidence |\n|------|--------|----------|\n");
        for (String rule : guard.ruleNames()) {
            List<Violation> found = violations.stream().filter(v -> v.rule().equals(rule)).toList();
            report.append("| ").append(rule).append(" | ").append(found.isEmpty() ? "PASS" : "FAIL").append(" | ")
                    .append(found.isEmpty()
                            ? files.size() + " files scanned"
                            : found.stream().map(v -> v.path() + " - " + v.message()).collect(Collectors.joining("; ")))
                    .append(" |\n");
        }
        report.append("\n## Verdict\n").append(violations.isEmpty() ? "PASS" : "FAIL").append('\n');
        return violations.isEmpty() ? StageResult.success(report.toString()) : StageResult.failure(report.toString());
    }

    private List<FileChange> readProductFiles() {
        Path product = root.resolve(WritePathRule.PRODUCT_DIR);
        if (!Files.isDirectory(product)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(product)) {
            List<FileChange> files = new ArrayList<>();
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (isScannable(product, file)) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    files.add(new FileChange(relative, Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot scan " + product, e);
        }
    }

    private static boolean isScannable(Path product, Path file) throws IOException {
        for (Path part : product.relativize(file)) {
            if (SKIPPED_DIRS.contains(part.toString())) {
                return false;
            }
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1)) && Files.size(file) <= MAX_FILE_BYTES;
    }
}

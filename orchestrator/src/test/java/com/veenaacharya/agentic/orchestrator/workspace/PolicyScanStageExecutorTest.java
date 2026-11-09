package com.veenaacharya.agentic.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.policy.PolicyConfig;
import com.veenaacharya.agentic.orchestrator.policy.PolicyProperties;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyScanStageExecutorTest {

    private static final StageContext CONTEXT = new StageContext("run-1",
            new StageDefinition("policy-scan", List.of(), Set.of()), ChangeType.GREENFIELD, "req", Map.of());

    @TempDir
    Path root;

    private PolicyScanStageExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new PolicyScanStageExecutor(new WorkspaceRoot(root),
                new PolicyConfig().scanGuard(new PolicyProperties(null)));
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void cleanTreePassesWithAFileCount() throws IOException {
        write("url-shortener/src/main/java/A.java", "class A {}");
        write("url-shortener/README.md", "# readme");

        StageResult result = executor.execute(CONTEXT);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).isEqualTo("""
                ## Findings

                | rule | status | evidence |
                |------|--------|----------|
                | secret-scan | PASS | 2 files scanned |
                | dependency-allowlist | PASS | 2 files scanned |

                ## Verdict
                PASS
                """);
    }

    @Test
    void secretAnywhereInTheTreeFailsTheStageWithoutRevealingIt() throws IOException {
        write("url-shortener/src/main/resources/application.properties", "db.password=hunter2hunter2\n");

        StageResult result = executor.execute(CONTEXT);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error())
                .contains("| secret-scan | FAIL | url-shortener/src/main/resources/application.properties"
                        + " - possible credential in config on line 1 |")
                .contains("| dependency-allowlist | PASS |")
                .contains("## Verdict\nFAIL")
                .doesNotContain("hunter2");
    }

    @Test
    void disallowedDependencyInPomFailsTheStage() throws IOException {
        write("url-shortener/pom.xml",
                "<project><dependencies><dependency><groupId>com.example.evil</groupId></dependency></dependencies></project>");

        StageResult result = executor.execute(CONTEXT);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).contains("| dependency-allowlist | FAIL | url-shortener/pom.xml - "
                + "dependency groupId 'com.example.evil' is not on the allowlist |");
    }

    @Test
    void buildOutputAndNonTextFilesAreNotScanned() throws IOException {
        write("url-shortener/target/classes/Leak.java", "String password = \"hunter2hunter2\";");
        write("url-shortener/data.bin", "password = \"hunter2hunter2\"");
        write("url-shortener/src/main/java/A.java", "class A {}");

        StageResult result = executor.execute(CONTEXT);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).contains("1 files scanned");
    }

    @Test
    void filesOutsideTheProductDirectoryAreOutOfScope() throws IOException {
        write("orchestrator/src/Other.java", "String password = \"hunter2hunter2\";");

        assertThat(executor.execute(CONTEXT).succeeded()).isTrue();
    }

    @Test
    void missingProductDirectoryPassesWithZeroFiles() {
        StageResult result = executor.execute(CONTEXT);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).contains("0 files scanned");
    }
}

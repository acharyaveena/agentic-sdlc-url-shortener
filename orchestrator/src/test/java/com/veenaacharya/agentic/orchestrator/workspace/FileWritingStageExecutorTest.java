package com.veenaacharya.agentic.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.agent.MockAgentRuntime;
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

class FileWritingStageExecutorTest {

    private static final String DESIGN_NO_SCHEMA = "## Schema changes\nNone\n";

    @TempDir
    Path root;

    private final MockAgentRuntime runtime = new MockAgentRuntime();
    private FileWritingStageExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new FileWritingStageExecutor(
                new AgentStageExecutor(runtime, new AgentProperties("mock", null, null, 0)),
                new PolicyConfig().writeGuard(new PolicyProperties(null)),
                new WorkspaceWriter(new WorkspaceRoot(root)));
    }

    private StageResult run(String stage) {
        StageContext context = new StageContext("run-1",
                new StageDefinition(stage, List.of(), Set.of()), ChangeType.GREENFIELD, "req",
                Map.of("design", DESIGN_NO_SCHEMA));
        return executor.execute(context);
    }

    private static String file(String path, String content) {
        return "FILE: " + path + "\n````\n" + content + "\n````\n";
    }

    @Test
    void writesAgentFilesAndReportsThem() throws IOException {
        runtime.respondWith("code", file("url-shortener/src/main/java/A.java", "class A {}"));

        StageResult result = run("code");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.files()).containsExactly("url-shortener/src/main/java/A.java");
        assertThat(result.output()).contains("FILE: url-shortener/src/main/java/A.java");
        assertThat(Files.readString(root.resolve("url-shortener/src/main/java/A.java"))).isEqualTo("class A {}\n");
    }

    @Test
    void bundledMockOutputsPassPolicyForTheirOwnStages() {
        for (String stage : List.of("code", "tests", "docs")) {
            StageResult result = run(stage);

            assertThat(result.succeeded()).as(stage + ": " + result.error()).isTrue();
            assertThat(result.files()).as(stage).isNotEmpty();
        }
        assertThat(root.resolve("url-shortener/src/main/java/com/veenaacharya/shortener/ShortCodeGenerator.java"))
                .exists();
        assertThat(root.resolve("url-shortener/README.md")).exists();
    }

    @Test
    void policyViolationWritesNothingAtAll() {
        runtime.respondWith("code", file("url-shortener/src/main/java/Ok.java", "class Ok {}")
                + file("orchestrator/src/main/java/Evil.java", "class Evil {}"));

        StageResult result = run("code");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).isEqualTo("policy violations, nothing was written:\n"
                + "write-path: orchestrator/src/main/java/Evil.java - writes are limited to url-shortener/");
        assertThat(root.resolve("url-shortener")).doesNotExist();
    }

    @Test
    void secretsInGeneratedFilesAreBlocked() {
        runtime.respondWith("code", file("url-shortener/src/main/java/Cfg.java",
                "class Cfg { String password = \"hunter2hunter2\"; }"));

        StageResult result = run("code");

        assertThat(result.error()).contains("secret-scan: url-shortener/src/main/java/Cfg.java")
                .doesNotContain("hunter2");
        assertThat(root.resolve("url-shortener")).doesNotExist();
    }

    @Test
    void schemaFilesAreBlockedWhenTheApprovedDesignDeclaresNoSchemaChange() {
        runtime.respondWith("code", file("url-shortener/src/main/resources/schema.sql", "create table t (id int);"));

        StageResult result = run("code");

        assertThat(result.error()).contains("schema-change")
                .contains("schema change is not declared in the approved design");
    }

    @Test
    void disallowedDependencyInPomIsBlocked() {
        runtime.respondWith("code", file("url-shortener/pom.xml",
                "<project><dependencies><dependency><groupId>com.example.evil</groupId></dependency>"
                        + "</dependencies></project>"));

        assertThat(run("code").error()).contains("dependency-allowlist: url-shortener/pom.xml");
    }

    @Test
    void stageCannotWriteIntoAnotherStagesArea() {
        runtime.respondWith("tests", file("url-shortener/src/main/java/Sneaky.java", "class Sneaky {}"));

        assertThat(run("tests").error()).contains("stage tests may only write to");
    }

    @Test
    void outputWithoutFileBlocksFails() {
        runtime.respondWith("code", "I would write a class A but here is only prose.");

        assertThat(run("code").error()).isEqualTo("agent output contains no FILE: blocks");
    }

    @Test
    void malformedOutputFailsWithTheParseProblem() {
        runtime.respondWith("code", "FILE: url-shortener/src/main/java/A.java\nclass A {}");

        assertThat(run("code").error()).isEqualTo(
                "cannot read agent output: FILE: url-shortener/src/main/java/A.java is not followed by a code fence");
    }

    @Test
    void agentFailureIsPassedThroughUnchanged() {
        runtime.failOn("code");

        StageResult result = run("code");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).isEqualTo("mock failure for stage code");
    }
}

package com.veenaacharya.agentic.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.policy.FileChange;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileChangeParserTest {

    private final FileChangeParser parser = new FileChangeParser();

    @Test
    void parsesSeveralFilesAndIgnoresProseBetweenThem() {
        String output = """
                Here is the code.

                FILE: url-shortener/src/main/java/A.java
                ````java
                class A {}
                ````

                Some commentary.

                FILE: url-shortener/src/main/java/B.java
                ````java
                class B {
                }
                ````

                Notes: done.
                """;

        assertThat(parser.parse(output)).containsExactly(
                new FileChange("url-shortener/src/main/java/A.java", "class A {}\n"),
                new FileChange("url-shortener/src/main/java/B.java", "class B {\n}\n"));
    }

    @Test
    void shorterFencesInsideAFileDoNotCloseIt() {
        String output = """
                FILE: url-shortener/README.md
                ````markdown
                # Title

                ```bash
                ./mvnw verify
                ```

                After.
                ````
                """;

        assertThat(parser.parse(output)).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo("url-shortener/README.md");
            assertThat(file.content()).isEqualTo("# Title\n\n```bash\n./mvnw verify\n```\n\nAfter.\n");
        });
    }

    @Test
    void acceptsPlainTripleBacktickFencesToo() {
        assertThat(parser.parse("FILE: a.txt\n```\nhi\n```\n")).containsExactly(new FileChange("a.txt", "hi\n"));
    }

    @Test
    void stripsDecorationFromPathsAndAllowsBlankLinesBeforeTheFence() {
        String output = "FILE: `url-shortener/x.txt`\n\n\n````text\nbody\n````\n";

        assertThat(parser.parse(output)).containsExactly(new FileChange("url-shortener/x.txt", "body\n"));
    }

    @Test
    void handlesWindowsLineEndings() {
        assertThat(parser.parse("FILE: a.txt\r\n````\r\nline1\r\nline2\r\n````\r\n"))
                .containsExactly(new FileChange("a.txt", "line1\nline2\n"));
    }

    @Test
    void outputWithoutFileBlocksYieldsNothing() {
        assertThat(parser.parse("Just an explanation.\n\n```java\nclass A {}\n```")).isEmpty();
    }

    @Test
    void fileLineWithoutAFenceIsRejected() {
        assertThatThrownBy(() -> parser.parse("FILE: a.txt\nplain text"))
                .isInstanceOf(FileChangeParseException.class)
                .hasMessage("FILE: a.txt is not followed by a code fence");
        assertThatThrownBy(() -> parser.parse("FILE: a.txt"))
                .hasMessage("FILE: a.txt is not followed by a code fence");
    }

    @Test
    void unterminatedFenceIsRejected() {
        assertThatThrownBy(() -> parser.parse("FILE: a.txt\n````\nnever closed\n"))
                .isInstanceOf(FileChangeParseException.class)
                .hasMessage("code fence for a.txt is never closed");
    }

    @Test
    void sameFileTwiceIsRejected() {
        String output = "FILE: a.txt\n```\n1\n```\nFILE: a.txt\n```\n2\n```\n";

        assertThatThrownBy(() -> parser.parse(output))
                .isInstanceOf(FileChangeParseException.class)
                .hasMessage("path appears twice: a.txt");
    }

    @Test
    void mockOutputsBundledWithTheOrchestratorAreParseable() {
        for (String stage : List.of("code", "tests", "docs")) {
            String output = new com.veenaacharya.agentic.orchestrator.agent.MockAgentRuntime().complete(
                    new com.veenaacharya.agentic.orchestrator.agent.AgentRequest("r", stage, "m", "s", "u"));

            assertThat(parser.parse(output)).as(stage).isNotEmpty()
                    .allSatisfy(f -> assertThat(f.path()).startsWith("url-shortener/"));
        }
    }
}

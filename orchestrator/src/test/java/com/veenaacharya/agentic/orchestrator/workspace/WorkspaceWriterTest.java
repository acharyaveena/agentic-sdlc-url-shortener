package com.veenaacharya.agentic.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.veenaacharya.agentic.orchestrator.policy.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceWriterTest {

    @TempDir
    Path root;

    private WorkspaceWriter writer;

    @BeforeEach
    void setUp() {
        writer = new WorkspaceWriter(new WorkspaceRoot(root));
    }

    @Test
    void writesFilesCreatingDirectoriesAndReturnsPaths() throws IOException {
        List<String> written = writer.write(List.of(
                new FileChange("url-shortener/src/main/java/a/A.java", "class A {}\n"),
                new FileChange("url-shortener/README.md", "caf\u00e9\n")));

        assertThat(written).containsExactly("url-shortener/src/main/java/a/A.java", "url-shortener/README.md");
        assertThat(Files.readString(root.resolve("url-shortener/src/main/java/a/A.java"))).isEqualTo("class A {}\n");
        assertThat(Files.readString(root.resolve("url-shortener/README.md"))).isEqualTo("caf\u00e9\n");
    }

    @Test
    void overwritesExistingFiles() throws IOException {
        writer.write(List.of(new FileChange("url-shortener/a.txt", "one")));
        writer.write(List.of(new FileChange("url-shortener/a.txt", "two")));

        assertThat(Files.readString(root.resolve("url-shortener/a.txt"))).isEqualTo("two");
    }

    @Test
    void refusesPathsOutsideTheProductDirectory() {
        assertThatThrownBy(() -> writer.write(List.of(new FileChange("orchestrator/x.txt", "x"))))
                .isInstanceOf(SecurityException.class)
                .hasMessage("refusing to write outside url-shortener/: orchestrator/x.txt");
        assertThatThrownBy(() -> writer.write(List.of(new FileChange("url-shortener/../escape.txt", "x"))))
                .isInstanceOf(SecurityException.class);
        assertThat(root.resolve("escape.txt")).doesNotExist();
        assertThat(root.resolve("orchestrator")).doesNotExist();
    }

    @Test
    void refusesToWriteThroughSymbolicLinks() throws IOException {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createDirectories(root.resolve("url-shortener"));
        try {
            Files.createSymbolicLink(root.resolve("url-shortener/link"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here");
        }

        assertThatThrownBy(() -> writer.write(List.of(new FileChange("url-shortener/link/x.txt", "x"))))
                .isInstanceOf(SecurityException.class)
                .hasMessage("refusing to write through a symbolic link: url-shortener/link/x.txt");
        assertThat(outside.resolve("x.txt")).doesNotExist();
    }
}

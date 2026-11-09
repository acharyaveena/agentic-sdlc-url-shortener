package com.veenaacharya.agentic.orchestrator.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WritePathRuleTest {

    private final WritePathRule rule = new WritePathRule();

    private List<Violation> check(String stage, String... paths) {
        List<FileChange> changes = java.util.Arrays.stream(paths).map(p -> new FileChange(p, "x")).toList();
        return rule.check(changes, new PolicyContext(stage, Map.of()));
    }

    @Test
    void eachStageMayWriteToItsOwnArea() {
        assertThat(check("code", "url-shortener/src/main/java/A.java", "url-shortener/pom.xml")).isEmpty();
        assertThat(check("tests", "url-shortener/src/test/java/ATest.java")).isEmpty();
        assertThat(check("docs", "url-shortener/README.md", "url-shortener/docs/api.md")).isEmpty();
    }

    @Test
    void stagesCannotWriteToEachOthersAreas() {
        assertThat(check("code", "url-shortener/src/test/java/ATest.java")).singleElement()
                .extracting(Violation::message).asString().startsWith("stage code may only write to");
        assertThat(check("tests", "url-shortener/src/main/java/A.java")).hasSize(1);
        assertThat(check("tests", "url-shortener/pom.xml")).hasSize(1);
        assertThat(check("docs", "url-shortener/src/main/java/A.java")).hasSize(1);
    }

    @Test
    void stagesWithoutAnAreaAreStillConfinedToTheProductDirectory() {
        assertThat(check("other", "url-shortener/anything/x.txt")).isEmpty();
        assertThat(check("other", "orchestrator/pom.xml")).singleElement()
                .extracting(Violation::message).isEqualTo("writes are limited to url-shortener/");
    }

    @Test
    void writesOutsideTheProductDirectoryAreRejected() {
        assertThat(check("code", "orchestrator/src/main/java/A.java", "pom.xml", ".github/workflows/ci.yml",
                "url-shortener-evil/src/main/A.java")).hasSize(4);
    }

    @Test
    void traversalAndUnnormalisedPathsAreRejected() {
        assertThat(check("code", "url-shortener/src/main/../../../pom.xml")).singleElement()
                .extracting(Violation::message).asString().contains("normalised");
        assertThat(check("code", "url-shortener/./src/main/A.java", "url-shortener//src/main/A.java")).hasSize(2);
    }

    @Test
    void absoluteDriveAndBackslashPathsAreRejected() {
        assertThat(check("code", "/etc/passwd", "C:/Windows/x.txt", "url-shortener\\src\\main\\A.java"))
                .extracting(Violation::message)
                .containsOnly("path must be relative and use forward slashes");
    }

    @Test
    void blankAndNullPathsAreRejected() {
        assertThat(check("code", " ")).singleElement().extracting(Violation::message).isEqualTo("path is empty");
        assertThat(rule.check(List.of(new FileChange(null, "x")), new PolicyContext("code", Map.of()))).hasSize(1);
    }

    @Test
    void violationNamesTheRuleAndPath() {
        Violation violation = check("code", "pom.xml").get(0);

        assertThat(violation.rule()).isEqualTo("write-path");
        assertThat(violation.path()).isEqualTo("pom.xml");
        assertThat(violation.toString()).isEqualTo("write-path: pom.xml - writes are limited to url-shortener/");
    }
}

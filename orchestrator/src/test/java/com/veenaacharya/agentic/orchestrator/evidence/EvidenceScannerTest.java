package com.veenaacharya.agentic.orchestrator.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceScannerTest {

    private final EvidenceScanner scanner = new EvidenceScanner(List.of("D:\\Work\\my-repo", "/home/me"));

    private List<String> scan(String content) {
        return scanner.scan(Map.of("a.md", content));
    }

    @Test
    void cleanEvidencePasses() {
        assertThat(scan("# Title\n\nShort links for `https://example.com/page`.\nContact: team@example.com\n"))
                .isEmpty();
        assertThat(scan("author: orchestrator@users.noreply.github.com")).isEmpty();
    }

    @Test
    void windowsAndUnixHomePathsAreFoundWithTheirLine() {
        assertThat(scan("ok\nat C:\\Users\\someone\\proj\\A.java")).containsExactly("a.md: local filesystem path on line 2");
        assertThat(scan("escaped in json: C:\\\\Users\\\\someone")).containsExactly("a.md: local filesystem path on line 1");
        assertThat(scan("/Users/someone/code")).hasSize(1);
        assertThat(scan("see /home/someone/code")).hasSize(1);
    }

    @Test
    void thisMachinesSpecificPathsAreFoundInAnyNotation() {
        assertThat(scan("root is D:\\Work\\my-repo here")).hasSize(1);
        assertThat(scan("root is D:/Work/my-repo here")).hasSize(1);
        assertThat(scan("{\"p\":\"D:\\\\Work\\\\my-repo\"}")).hasSize(1);
    }

    @Test
    void personalEmailAddressesAreFlaggedButNeverEchoed() {
        List<String> problems = scan("reviewer: jane.doe@gmail.com");

        assertThat(problems).containsExactly("a.md: email address on line 1");
        assertThat(String.join(" ", problems)).doesNotContain("jane");
    }

    @Test
    void secretsAreFlaggedWithoutTheirValue() {
        List<String> problems = scan("String password = \"hunter2hunter2\";");

        assertThat(problems).containsExactly("a.md: possible hard-coded credential on line 1");
    }

    @Test
    void everyFileIsCheckedAndReportedByName() {
        List<String> problems = scanner.scan(Map.of("one.md", "fine", "two.md", "C:\\Users\\x\\y"));

        assertThat(problems).containsExactly("two.md: local filesystem path on line 1");
    }

    @Test
    void shortOrBlankMachinePathsAreIgnoredSoTheyCannotMatchEverything() {
        EvidenceScanner lenient = new EvidenceScanner(java.util.Arrays.asList("", "/", null));

        assertThat(lenient.scan(Map.of("a.md", "plain text with / slashes"))).isEmpty();
    }
}

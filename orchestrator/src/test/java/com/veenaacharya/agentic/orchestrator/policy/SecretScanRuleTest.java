package com.veenaacharya.agentic.orchestrator.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretScanRuleTest {

    private final SecretScanRule rule = new SecretScanRule();

    private List<Violation> scan(String content) {
        return rule.check(List.of(new FileChange("url-shortener/src/main/A.java", content)),
                new PolicyContext("code", Map.of()));
    }

    // Test secrets are assembled at runtime so this file holds no literal that secret scanners would flag.
    @Test
    void detectsKnownCredentialFormats() {
        assertThat(scan("String k = \"" + "AKIA" + "ABCDEFGHIJKLMNOP" + "\";")).singleElement()
                .extracting(Violation::message).isEqualTo("possible AWS access key id on line 1");
        assertThat(scan("-----BEGIN RSA " + "PRIVATE KEY-----")).singleElement()
                .extracting(Violation::message).isEqualTo("possible private key on line 1");
        assertThat(scan("token=" + "gh" + "p_" + "a".repeat(36))).extracting(Violation::message)
                .containsExactly("possible GitHub token on line 1");
        assertThat(scan("hook xo" + "xb-1234567890-abcdefghij")).extracting(Violation::message)
                .containsExactly("possible Slack token on line 1");
    }

    @Test
    void detectsHardCodedCredentialsInCodeAndConfig() {
        assertThat(scan("String password = \"hunter2hunter2\";")).extracting(Violation::message)
                .containsExactly("possible hard-coded credential on line 1");
        assertThat(scan("spring.datasource.password=supersecret1")).extracting(Violation::message)
                .containsExactly("possible credential in config on line 1");
        assertThat(scan("app:\n  api-key: abcdefgh12345")).extracting(Violation::message)
                .containsExactly("possible credential in config on line 2");
    }

    @Test
    void reportsLineNumbersAndNeverTheSecretValue() {
        List<Violation> violations = scan("class A {}\n\nString password = \"hunter2hunter2\";\n");

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.message()).contains("line 3").doesNotContain("hunter2");
            assertThat(v.rule()).isEqualTo("secret-scan");
            assertThat(v.path()).isEqualTo("url-shortener/src/main/A.java");
        });
    }

    @Test
    void ignoresPlaceholdersEmptyValuesAndPlainCode() {
        assertThat(scan("String password = \"\";")).isEmpty();
        assertThat(scan("spring.datasource.password=${DB_PASSWORD}")).isEmpty();
        assertThat(scan("password: ${DB_PASSWORD}")).isEmpty();
        assertThat(scan("String p = request.getPassword();")).isEmpty();
        assertThat(scan("password=short")).isEmpty();
        assertThat(scan("// a link shortener with tokens of length seven")).isEmpty();
    }

    @Test
    void reportsEachOffendingLineOnceAcrossFiles() {
        List<Violation> violations = rule.check(List.of(
                new FileChange("a.properties", "db.password=abcdefgh123\nokay=1\nx.secret=zzzzzzzzzz"),
                new FileChange("b.txt", "nothing here")), new PolicyContext("code", Map.of()));

        assertThat(violations).extracting(Violation::path, v -> v.message().substring(v.message().indexOf("line")))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a.properties", "line 1"),
                        org.assertj.core.groups.Tuple.tuple("a.properties", "line 3"));
    }
}

package com.veenaacharya.agentic.orchestrator.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultPolicyGuardTest {

    private static final PolicyContext CONTEXT = new PolicyContext("code", Map.of());

    @Test
    void collectsViolationsFromEveryRuleInOrder() {
        PolicyGuard guard = new DefaultPolicyGuard(List.of(new WritePathRule(), new SecretScanRule()));
        List<FileChange> changes = List.of(new FileChange("pom.xml", "password = \"abcdefgh1234\""));

        assertThat(guard.check(changes, CONTEXT)).extracting(Violation::rule)
                .containsExactly("write-path", "secret-scan");
    }

    @Test
    void cleanChangesProduceNoViolations() {
        PolicyGuard guard = new DefaultPolicyGuard(List.of(new WritePathRule(), new SecretScanRule()));

        assertThat(guard.check(List.of(new FileChange("url-shortener/src/main/A.java", "class A {}")), CONTEXT))
                .isEmpty();
    }

    @Test
    void exposesRuleNames() {
        PolicyGuard guard = new DefaultPolicyGuard(List.of(new SecretScanRule(),
                new DependencyAllowlistRule(Set.of("a"))));

        assertThat(guard.ruleNames()).containsExactly("secret-scan", "dependency-allowlist");
    }

    @Test
    void configuredGuardsDifferInPreventiveAndDetectiveRules() {
        PolicyConfig config = new PolicyConfig();
        PolicyProperties properties = new PolicyProperties(null);

        assertThat(config.writeGuard(properties).ruleNames())
                .containsExactly("write-path", "secret-scan", "dependency-allowlist", "schema-change");
        assertThat(config.scanGuard(properties).ruleNames())
                .containsExactly("secret-scan", "dependency-allowlist");
        assertThat(properties.allowedGroupIds()).contains("org.springframework.boot", "com.h2database");
        assertThat(new PolicyProperties(List.of("x.y")).allowedGroupIds()).containsExactly("x.y");
    }

    @Test
    void bundledAllowlistPermitsTheReviewedOpenApiParserAndCoveragePluginButNotOtherLibraries() {
        PolicyGuard guard = new PolicyConfig().writeGuard(new PolicyProperties(
                List.of("org.springframework.boot", "io.swagger.parser.v3", "org.jacoco")));
        String pom = "<project><dependencies>"
                + "<dependency><groupId>io.swagger.parser.v3</groupId><artifactId>swagger-parser</artifactId></dependency>"
                + "<dependency><groupId>com.example.other</groupId><artifactId>x</artifactId></dependency>"
                + "</dependencies><build><plugins><plugin><groupId>org.jacoco</groupId>"
                + "<artifactId>jacoco-maven-plugin</artifactId></plugin></plugins></build></project>";

        assertThat(guard.check(List.of(new FileChange("url-shortener/pom.xml", pom)),
                new PolicyContext("code", Map.of()))).extracting(Violation::message)
                .containsExactly("dependency groupId 'com.example.other' is not on the allowlist");
    }
}

package com.veenaacharya.agentic.orchestrator.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DependencyAllowlistRuleTest {

    private final DependencyAllowlistRule rule =
            new DependencyAllowlistRule(Set.of("org.springframework.boot", "org.apache.maven.plugins"));

    private List<Violation> check(String path, String content) {
        return rule.check(List.of(new FileChange(path, content)), new PolicyContext("code", Map.of()));
    }

    private static String pom(String body) {
        return "<project><parent><groupId>some.parent</groupId></parent>" + body + "</project>";
    }

    @Test
    void allowedDependenciesAndPluginsPass() {
        String pom = pom("<dependencies><dependency><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>"
                + "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                + "<artifactId>maven-surefire-plugin</artifactId></plugin></plugins></build>");

        assertThat(check("url-shortener/pom.xml", pom)).isEmpty();
    }

    @Test
    void unlistedDependencyIsRejectedWithItsGroupId() {
        String pom = pom("<dependencies><dependency>\n<groupId>com.example.evil</groupId>\n"
                + "<artifactId>lib</artifactId>\n</dependency></dependencies>");

        assertThat(check("url-shortener/pom.xml", pom)).singleElement().satisfies(v -> {
            assertThat(v.rule()).isEqualTo("dependency-allowlist");
            assertThat(v.message()).isEqualTo("dependency groupId 'com.example.evil' is not on the allowlist");
        });
    }

    @Test
    void unlistedPluginIsRejected() {
        String pom = pom("<build><plugins><plugin><groupId>com.example.evil</groupId>"
                + "<artifactId>run-code</artifactId></plugin></plugins></build>");

        assertThat(check("url-shortener/pom.xml", pom)).extracting(Violation::message)
                .containsExactly("plugin groupId 'com.example.evil' is not on the allowlist");
    }

    @Test
    void parentGroupIdIsNotTreatedAsADependency() {
        assertThat(check("url-shortener/pom.xml", pom(""))).isEmpty();
    }

    @Test
    void addingRemoteRepositoriesIsRejected() {
        String pom = pom("<repositories><repository><id>x</id><url>https://example.com/m2</url>"
                + "</repository></repositories>");

        assertThat(check("url-shortener/pom.xml", pom)).extracting(Violation::message)
                .containsExactly("adding remote repositories is not allowed");
    }

    @Test
    void multipleBadDependenciesAreAllReported() {
        String pom = pom("<dependencies><dependency><groupId>a.b</groupId></dependency>"
                + "<dependency><groupId>org.springframework.boot</groupId></dependency>"
                + "<dependency><groupId>c.d</groupId></dependency></dependencies>");

        assertThat(check("url-shortener/pom.xml", pom)).hasSize(2);
    }

    @Test
    void onlyPomFilesAreChecked() {
        assertThat(check("url-shortener/src/main/A.java", "<dependency><groupId>x.y</groupId></dependency>")).isEmpty();
    }
}

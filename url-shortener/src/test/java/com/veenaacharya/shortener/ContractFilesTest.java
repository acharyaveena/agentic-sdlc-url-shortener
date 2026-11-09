package com.veenaacharya.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

class ContractFilesTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> openApi() throws Exception {
        try (InputStream in = new ClassPathResource("openapi.yaml").getInputStream()) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responses(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        return (Map<String, Object>) operation.get("responses");
    }

    @Test
    @SuppressWarnings("unchecked")
    void openApiDescribesEveryEndpointAndErrorStatus() throws Exception {
        Map<String, Object> spec = openApi();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        assertThat(paths).containsKeys("/api/v1/links", "/api/v1/links/{code}/stats", "/{code}");

        assertThat(responses(paths, "/api/v1/links", "post").keySet().stream().map(String::valueOf))
                .contains("201", "400", "429", "503");
        assertThat(responses(paths, "/api/v1/links/{code}/stats", "get").keySet().stream().map(String::valueOf))
                .contains("200", "404");
        assertThat(responses(paths, "/{code}", "get").keySet().stream().map(String::valueOf))
                .contains("302", "404", "410");
    }

    @Test
    @SuppressWarnings("unchecked")
    void openApiDefinesRequestAndResponseSchemas() throws Exception {
        Map<String, Object> components = (Map<String, Object>) openApi().get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        assertThat(schemas).containsKeys("CreateLinkRequest", "LinkResponse", "LinkStats", "ProblemDetail");
    }

    @Test
    void schemaDefinesLinksTableWithUniqueShortCode() throws Exception {
        String ddl;
        try (InputStream in = new ClassPathResource("schema.sql").getInputStream()) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(ddl).containsIgnoringCase("CREATE TABLE IF NOT EXISTS links");
        assertThat(ddl).containsPattern("(?is)unique\\s*\\(\\s*short_code\\s*\\)");
        assertThat(ddl).containsIgnoringCase("click_count");
        assertThat(ddl).containsIgnoringCase("VARCHAR(2048)");
    }
}

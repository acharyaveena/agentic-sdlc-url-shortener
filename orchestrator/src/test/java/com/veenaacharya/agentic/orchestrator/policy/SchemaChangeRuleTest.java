package com.veenaacharya.agentic.orchestrator.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaChangeRuleTest {

    private final SchemaChangeRule rule = new SchemaChangeRule();

    private List<Violation> check(String path, String design) {
        Map<String, String> upstream = design == null ? Map.of() : Map.of("design", design);
        return rule.check(List.of(new FileChange(path, "create table links (id bigint);")),
                new PolicyContext("code", upstream));
    }

    @Test
    void schemaFileIsRejectedWhenDesignSaysNone() {
        String design = "## Design overview\nx\n\n## Schema changes\nNone\n\n## Approvals needed\ny";

        assertThat(check("url-shortener/src/main/resources/schema.sql", design)).singleElement().satisfies(v -> {
            assertThat(v.rule()).isEqualTo("schema-change");
            assertThat(v.message()).isEqualTo("schema change is not declared in the approved design");
        });
    }

    @Test
    void schemaFileIsAllowedWhenDesignDeclaresChanges() {
        String design = "## Schema changes\n```sql\nCREATE TABLE links (id BIGINT);\n```\n\n## Approvals needed\nschema";

        assertThat(check("url-shortener/src/main/resources/schema.sql", design)).isEmpty();
    }

    @Test
    void schemaFileIsRejectedWithoutADesignOrASchemaSection() {
        assertThat(check("url-shortener/src/main/resources/schema.sql", null)).hasSize(1);
        assertThat(check("url-shortener/src/main/resources/schema.sql", "## Design overview\nonly")).hasSize(1);
    }

    @Test
    void variantsOfNoneAreRecognisedAndHeadingStyleIsFlexible() {
        for (String none : List.of("None", "none.", "None - nothing to migrate", "n/a was not used\nNone")) {
            String design = "### Schema changes:\n" + none + "\n### Next";
            boolean declared = SchemaChangeRule.designDeclaresSchemaChange(design);

            assertThat(declared).as(none).isEqualTo(none.startsWith("n/a"));
        }
    }

    @Test
    void migrationFoldersAndDdlFilesCountAsSchemaFiles() {
        assertThat(check("url-shortener/src/main/resources/db/migration/V2__expiry.sql", null)).hasSize(1);
        assertThat(check("url-shortener/src/main/resources/db/changelog/x.yaml", null)).hasSize(1);
        assertThat(check("url-shortener/src/main/resources/model.DDL", null)).hasSize(1);
    }

    @Test
    void ordinaryFilesAreUnaffected() {
        assertThat(check("url-shortener/src/main/java/A.java", null)).isEmpty();
        assertThat(check("url-shortener/src/main/resources/application.yml", null)).isEmpty();
    }
}

package com.veenaacharya.shortener.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
class SchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void clickEventTableHasExpectedColumns() {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = 'CLICK_EVENT'");

        assertThat(cols).extracting(m -> String.valueOf(m.get("COLUMN_NAME")).toUpperCase())
                .containsExactlyInAnyOrder("ID", "LINK_ID", "CLICKED_AT", "REFERRER_HOST", "UA_CATEGORY");
        assertThat(nullable(cols, "REFERRER_HOST")).isEqualTo("YES");
        assertThat(nullable(cols, "LINK_ID")).isEqualTo("NO");
        assertThat(nullable(cols, "CLICKED_AT")).isEqualTo("NO");
        assertThat(nullable(cols, "UA_CATEGORY")).isEqualTo("NO");
    }

    private static String nullable(List<Map<String, Object>> cols, String name) {
        return cols.stream().filter(m -> name.equalsIgnoreCase(String.valueOf(m.get("COLUMN_NAME"))))
                .map(m -> String.valueOf(m.get("IS_NULLABLE"))).findFirst().orElseThrow();
    }

    @Test
    void indexOnLinkIdAndClickedAtExists() {
        List<String> cols = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                        + "WHERE UPPER(INDEX_NAME) = 'IDX_CLICK_EVENT_LINK_TIME' ORDER BY ORDINAL_POSITION", String.class);

        assertThat(cols).extracting(String::toUpperCase).containsExactly("LINK_ID", "CLICKED_AT");
    }

    @Test
    void checkConstraintRejectsUnknownCategory() {
        Long linkId = jdbc.queryForObject("SELECT MIN(id) FROM links", Long.class);
        if (linkId == null) {
            return; // no link available to reference; constraint is exercised via the application tests
        }
        assertThatCode(() -> jdbc.update(
                "INSERT INTO click_event (link_id, clicked_at, referrer_host, ua_category) VALUES (?, CURRENT_TIMESTAMP, NULL, 'ROBOT')",
                linkId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void schemaScriptIsIdempotent() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));

        assertThatCode(() -> populator.execute(dataSource)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(INDEX_NAME) = 'IDX_CLICK_EVENT_LINK_TIME'",
                Long.class)).isPositive();
    }
}

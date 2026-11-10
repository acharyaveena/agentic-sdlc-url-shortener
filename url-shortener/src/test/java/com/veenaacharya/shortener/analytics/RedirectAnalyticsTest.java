package com.veenaacharya.shortener.analytics;

import com.veenaacharya.shortener.support.TestLinkSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:redirectanalytics;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "app.rate-limit.requests=1000"
})
@AutoConfigureMockMvc
class RedirectAnalyticsTest {

    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) Secret-UA-Marker/1.0";
    private static final String REFERER = "https://Example.COM/private/path?token=abc123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClickEventRepository events;

    @Test
    void redirectStoresExactlyOneEventWithHostAndCategory() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        long id = TestLinkSupport.linkId(jdbc, code);

        mvc.perform(get("/" + code).header("User-Agent", UA).header("Referer", REFERER))
                .andExpect(status().is3xxRedirection());

        List<ClickEvent> stored = events.findInRange(id, Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).linkId()).isEqualTo(id);
        assertThat(stored.get(0).referrerHost()).isEqualTo("example.com");
        assertThat(stored.get(0).uaCategory()).isEqualTo(UserAgentCategory.BROWSER);
        assertThat(stored.get(0).clickedAt()).isNotNull();
    }

    @Test
    void redirectWithoutHeadersStoresNullHostAndOther() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        long id = TestLinkSupport.linkId(jdbc, code);

        mvc.perform(get("/" + code)).andExpect(status().is3xxRedirection());

        List<ClickEvent> stored = events.findInRange(id, Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).referrerHost()).isNull();
        assertThat(stored.get(0).uaCategory()).isEqualTo(UserAgentCategory.OTHER);
    }

    @Test
    void counterIncrementsByOneAsBefore() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        mvc.perform(get("/api/v1/links/" + code + "/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.clickCount").value(0));

        mvc.perform(get("/" + code).header("User-Agent", UA)).andExpect(status().is3xxRedirection());

        mvc.perform(get("/api/v1/links/" + code + "/stats")).andExpect(status().isOk()).andExpect(jsonPath("$.clickCount").value(1));
    }

    @Test
    void unknownCodeIs404AndStoresNothing() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM click_event", Long.class);

        mvc.perform(get("/zzNoSuch9").header("User-Agent", UA)).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM click_event", Long.class)).isEqualTo(before);
    }

    @Test
    void expiredLinkIs410AndStoresNothing() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        long id = TestLinkSupport.linkId(jdbc, code);
        TestLinkSupport.expire(jdbc, code);

        mvc.perform(get("/" + code).header("User-Agent", UA).header("Referer", REFERER)).andExpect(status().isGone());

        assertThat(TestLinkSupport.eventCount(jdbc, id)).isZero();
    }

    @Test
    void schemaHasNoPersonalDataColumns() {
        Set<String> columns = jdbc.queryForList(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = 'CLICK_EVENT'", String.class)
                .stream().map(String::toUpperCase).collect(Collectors.toSet());

        assertThat(columns).containsExactlyInAnyOrder("ID", "LINK_ID", "CLICKED_AT", "REFERRER_HOST", "UA_CATEGORY");
    }

    @Test
    void storedRowContainsNoRawHeaderOrIpData() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        long id = TestLinkSupport.linkId(jdbc, code);

        mvc.perform(get("/" + code).header("User-Agent", UA).header("Referer", REFERER)
                        .with(r -> {
                            r.setRemoteAddr("203.0.113.77");
                            return r;
                        }))
                .andExpect(status().is3xxRedirection());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM click_event WHERE link_id = ?", id);
        String all = row.values().stream().map(String::valueOf).collect(Collectors.joining("|"));
        assertThat(row.get("REFERRER_HOST")).isEqualTo("example.com");
        assertThat(row.get("UA_CATEGORY")).isEqualTo("BROWSER");
        assertThat(all).doesNotContain("Secret-UA-Marker").doesNotContain("Mozilla")
                .doesNotContain("private").doesNotContain("abc123").doesNotContain("203.0.113.77").doesNotContain("https://");
    }
}

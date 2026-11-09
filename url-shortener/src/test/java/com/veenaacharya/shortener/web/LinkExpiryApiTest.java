package com.veenaacharya.shortener.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veenaacharya.shortener.link.ExpiredLinkCleanupJob;
import com.veenaacharya.shortener.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:linkexpiry;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "app.base-url=https://short.example",
        "app.rate-limit.requests=1000",
        "app.link.default-ttl=P30D",
        "app.link.cleanup-grace=P7D"
})
@AutoConfigureMockMvc
class LinkExpiryApiTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ExpiredLinkCleanupJob cleanupJob;

    @BeforeEach
    void reset() {
        clock.set(START);
        jdbc.update("DELETE FROM links");
    }

    private String create(String url, Long ttlSeconds) throws Exception {
        Map<String, Object> body = ttlSeconds == null ? Map.of("url", url) : Map.of("url", url, "ttlSeconds", ttlSeconds);
        return mvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createWithExplicitTtlReturnsMatchingExpiresAt() throws Exception {
        String body = create("https://example.com/page", 3600L);

        Instant expiresAt = Instant.parse(mapper.readTree(body).get("expiresAt").asText());
        assertThat(expiresAt).isEqualTo(START.plusSeconds(3600));
    }

    @Test
    void createWithoutTtlFallsBackToConfiguredDefault() throws Exception {
        String body = create("https://example.com/page", null);

        Instant expiresAt = Instant.parse(mapper.readTree(body).get("expiresAt").asText());
        assertThat(expiresAt).isEqualTo(START.plus(Duration.parse("P30D")));
    }

    @Test
    void ttlBelowMinimumReturns400() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/page\",\"ttlSeconds\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ttlAboveMaximumReturns400() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/page\",\"ttlSeconds\":99999999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void redirectSucceedsBeforeExpiryAndFailsWithGoneAfter() throws Exception {
        String code = mapper.readTree(create("https://example.com/page", 60L)).get("code").asText();

        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/page"));

        clock.advance(Duration.ofSeconds(61));

        mvc.perform(get("/" + code))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(410));

        assertThat(jdbc.queryForObject(
                "SELECT click_count FROM links WHERE short_code = ?", Long.class, code)).isEqualTo(1L);
    }

    @Test
    void statsRemainAvailableForAnExpiredLink() throws Exception {
        String code = mapper.readTree(create("https://example.com/page", 60L)).get("code").asText();
        clock.advance(Duration.ofSeconds(61));

        mvc.perform(get("/api/v1/links/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void existingLinksWithNoExpiryNeverGoneAndSurviveCleanup() throws Exception {
        jdbc.update("INSERT INTO links (short_code, long_url, click_count, created_at, expires_at) "
                + "VALUES (?, ?, 0, ?, NULL)", "legacy01", "https://example.com/legacy", java.sql.Timestamp.from(START));

        clock.advance(Duration.ofDays(400));
        mvc.perform(get("/legacy01")).andExpect(status().isFound());

        cleanupJob.purge();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM links WHERE short_code = ?", Integer.class, "legacy01")).isEqualTo(1);
    }

    @Test
    void cleanupJobDeletesOnlyLinksExpiredBeyondTheGracePeriod() throws Exception {
        jdbc.update("INSERT INTO links (short_code, long_url, click_count, created_at, expires_at) "
                        + "VALUES (?, ?, 0, ?, ?)",
                "stale001", "https://example.com/stale", java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START.plusSeconds(60)));
        jdbc.update("INSERT INTO links (short_code, long_url, click_count, created_at, expires_at) "
                        + "VALUES (?, ?, 0, ?, ?)",
                "recent01", "https://example.com/recent", java.sql.Timestamp.from(START),
                java.sql.Timestamp.from(START.plusSeconds(60)));

        // Advance so stale001 (expired at START+60s) is beyond the 7-day grace, while recent01
        // (expires just before "now") is still inside it.
        clock.set(START.plusSeconds(60).plus(Duration.parse("P7D")).plusSeconds(1));
        jdbc.update("UPDATE links SET expires_at = ? WHERE short_code = ?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), "recent01");

        cleanupJob.purge();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM links WHERE short_code = ?", Integer.class, "stale001")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM links WHERE short_code = ?", Integer.class, "recent01")).isEqualTo(1);
    }
}

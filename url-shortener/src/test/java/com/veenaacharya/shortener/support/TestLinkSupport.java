package com.veenaacharya.shortener.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared helpers for analytics tests. */
public final class TestLinkSupport {

    private TestLinkSupport() {
    }

    public static String createLink(MockMvc mvc) throws Exception {
        String body = mvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/landing\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    public static long linkId(JdbcTemplate jdbc, String code) {
        Long id = jdbc.queryForObject("SELECT id FROM links WHERE short_code = ?", Long.class, code);
        return id;
    }

    public static void expire(JdbcTemplate jdbc, String code) {
        jdbc.update("UPDATE links SET expires_at = ? WHERE short_code = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), code);
    }

    public static long eventCount(JdbcTemplate jdbc, long linkId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM click_event WHERE link_id = ?", Long.class, linkId);
        return n;
    }
}

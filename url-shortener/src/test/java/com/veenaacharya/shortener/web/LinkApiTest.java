package com.veenaacharya.shortener.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:linkapi;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "app.base-url=https://short.example",
        "app.rate-limit.requests=1000"
})
@AutoConfigureMockMvc
class LinkApiTest {

    private static final String PREFIX = "https://example.com/";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM links");
    }

    private ResultActions create(String url) throws Exception {
        return mvc.perform(post("/api/v1/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("url", url))));
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM links", Integer.class);
    }

    private long clicks(String code) {
        return jdbc.queryForObject("SELECT click_count FROM links WHERE short_code = ?", Long.class, code);
    }

    private void seed(String code, String url, long clicks) {
        jdbc.update("INSERT INTO links (short_code, long_url, click_count, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                code, url, clicks);
    }

    @Test
    void createReturns201WithCodeShortUrlAndStoresZeroClicks() throws Exception {
        String body = create("https://example.com/page")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("^[0-9A-Za-z]{7}$")))
                .andExpect(jsonPath("$.longUrl").value("https://example.com/page"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();

        String code = mapper.readTree(body).get("code").asText();
        assertThat(mapper.readTree(body).get("shortUrl").asText()).isEqualTo("https://short.example/" + code);
        assertThat(rowCount()).isEqualTo(1);
        assertThat(clicks(code)).isZero();
        assertThat(jdbc.queryForObject("SELECT long_url FROM links WHERE short_code = ?", String.class, code))
                .isEqualTo("https://example.com/page");
    }

    @Test
    void createLocationHeaderPointsToShortUrl() throws Exception {
        String body = create("https://example.com/loc").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = mapper.readTree(body).get("code").asText();

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/loc2\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("https://short.example/")));
        assertThat(code).hasSize(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/f", "javascript:alert(1)", "file:///etc/passwd",
            "mailto:a@example.com", "example.com/page", "", "   ", "http://exa mple.com/",
            "https://user:pw@example.com/"
    })
    void invalidUrlsReturn400ProblemAndStoreNothing(String url) throws Exception {
        create(url)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        assertThat(rowCount()).isZero();
    }

    @Test
    void missingUrlFieldReturns400() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(rowCount()).isZero();
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\": "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(rowCount()).isZero();
    }

    @Test
    void urlOf2049CharactersReturns400() throws Exception {
        create(PREFIX + "a".repeat(2049 - PREFIX.length()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(rowCount()).isZero();
    }

    @Test
    void urlOfExactly2048CharactersReturns201() throws Exception {
        String url = PREFIX + "a".repeat(2048 - PREFIX.length());
        create(url).andExpect(status().isCreated()).andExpect(jsonPath("$.longUrl").value(url));
        assertThat(rowCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://short.example/abc", "HTTP://SHORT.EXAMPLE:9999/x",
            "http://localhost:8080/x", "http://127.0.0.1/x", "http://[::1]/x"
    })
    void selfReferencingUrlsReturn400(String url) throws Exception {
        create(url)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("Self-referencing links are not allowed")));
        assertThat(rowCount()).isZero();
    }

    @Test
    void redirectReturns302AndIncrementsClicksByOne() throws Exception {
        seed("abc1234", "https://example.com/target?x=1", 0);

        mvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target?x=1"));

        assertThat(clicks("abc1234")).isEqualTo(1);
    }

    @Test
    void unknownCodeReturns404OnRedirectAndStatsWithoutChangingCounts() throws Exception {
        seed("abc1234", "https://example.com/target", 3);

        mvc.perform(get("/zzzzzzz")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/links/zzzzzzz/stats"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(clicks("abc1234")).isEqualTo(3);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void malformedCodeReturns404() throws Exception {
        mvc.perform(get("/abc-def")).andExpect(status().isNotFound());
        mvc.perform(get("/" + "a".repeat(17))).andExpect(status().isNotFound());
    }

    @Test
    void statsReflectsNumberOfRedirects() throws Exception {
        seed("stat123", "https://example.com/stats", 0);
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/stat123")).andExpect(status().isFound());
        }

        mvc.perform(get("/api/v1/links/stat123/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("stat123"))
                .andExpect(jsonPath("$.longUrl").value("https://example.com/stats"))
                .andExpect(jsonPath("$.clickCount").value(4))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void statsDoesNotCountAsClick() throws Exception {
        seed("stat456", "https://example.com/s", 2);

        mvc.perform(get("/api/v1/links/stat456/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(2));

        assertThat(clicks("stat456")).isEqualTo(2);
    }

    @Test
    void endToEndCreateRedirectStats() throws Exception {
        String body = create("https://example.com/e2e").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = mapper.readTree(body).get("code").asText();

        mvc.perform(get("/" + code)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/e2e"));
        mvc.perform(get("/api/v1/links/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(1));
    }
}

package com.veenaacharya.shortener.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.veenaacharya.shortener.code.ShortCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:collision;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "app.base-url=https://short.example",
        "app.rate-limit.requests=1000"
})
@AutoConfigureMockMvc
class CollisionTest {

    private static final String BODY = "{\"url\":\"https://example.com/page\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    ShortCodeGenerator generator;

    @BeforeEach
    void setUp() {
        reset(generator);
        jdbc.update("DELETE FROM links");
        jdbc.update("INSERT INTO links (short_code, long_url, click_count, created_at) "
                + "VALUES ('dup', 'https://example.com/existing', 0, CURRENT_TIMESTAMP)");
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM links", Integer.class);
    }

    @Test
    void succeedsWithThirdCodeAfterTwoCollisions() throws Exception {
        when(generator.next()).thenReturn("dup", "dup", "new");

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("new"))
                .andExpect(jsonPath("$.shortUrl").value("https://short.example/new"));

        verify(generator, times(3)).next();
        assertThat(rowCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT long_url FROM links WHERE short_code = 'new'", String.class))
                .isEqualTo("https://example.com/page");
    }

    @Test
    void returns503AfterFiveCollisionsAndStoresNothing() throws Exception {
        when(generator.next()).thenReturn("dup");

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503));

        verify(generator, times(5)).next();
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void succeedsOnFifthAttemptAfterFourCollisions() throws Exception {
        when(generator.next()).thenReturn("dup", "dup", "dup", "dup", "fifth");

        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("fifth"));

        verify(generator, times(5)).next();
    }
}

package com.veenaacharya.shortener.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.veenaacharya.shortener.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ratelimit;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "app.base-url=https://short.example",
        "app.rate-limit.requests=10",
        "app.rate-limit.window=PT1M"
})
@AutoConfigureMockMvc
class RateLimitTest {

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

    @BeforeEach
    void reset() {
        clock.set(START);
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MockHttpServletRequestBuilder create(String ip) {
        return post("/api/v1/links")
                .with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/page\"}");
    }

    private void exhaust(String ip) throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(create(ip)).andExpect(status().isCreated());
        }
    }

    @Test
    void eleventhCreationFromSameClientReturns429WithRetryAfter() throws Exception {
        exhaust("10.0.0.1");

        mvc.perform(create("10.0.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void retryAfterIsPositiveInteger() throws Exception {
        exhaust("10.0.0.5");

        String retryAfter = mvc.perform(create("10.0.0.5"))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");

        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
    }

    @Test
    void differentClientAddressIsNotLimited() throws Exception {
        exhaust("10.0.0.6");
        mvc.perform(create("10.0.0.6")).andExpect(status().isTooManyRequests());

        mvc.perform(create("10.0.0.2")).andExpect(status().isCreated());
    }

    @Test
    void redirectAndStatsAreNotRateLimited() throws Exception {
        exhaust("10.0.0.3");
        mvc.perform(create("10.0.0.3")).andExpect(status().isTooManyRequests());

        jdbc.update("MERGE INTO links (short_code, long_url, click_count, created_at) KEY (short_code) "
                + "VALUES ('rl12345', 'https://example.com/rl', 0, CURRENT_TIMESTAMP)");

        for (int i = 0; i < 15; i++) {
            mvc.perform(get("/rl12345").with(from("10.0.0.3")))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/rl"));
        }
        mvc.perform(get("/api/v1/links/rl12345/stats").with(from("10.0.0.3"))).andExpect(status().isOk());
    }

    @Test
    void clientCanCreateAgainAfterWindowPasses() throws Exception {
        exhaust("10.0.0.4");
        mvc.perform(create("10.0.0.4")).andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofSeconds(61));

        mvc.perform(create("10.0.0.4")).andExpect(status().isCreated());
    }
}

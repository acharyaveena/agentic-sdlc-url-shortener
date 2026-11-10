package com.veenaacharya.shortener.analytics;

import com.veenaacharya.shortener.support.TestLinkSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analyticscontroller;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "app.rate-limit.requests=1000"
})
@AutoConfigureMockMvc
class AnalyticsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClickEventRepository events;

    private long seed(String code, String iso, String host, UserAgentCategory ua) {
        long id = TestLinkSupport.linkId(jdbc, code);
        events.save(new ClickEvent(null, id, Instant.parse(iso), host, ua));
        return id;
    }

    private String url(String code) {
        return "/api/v1/links/" + code + "/analytics";
    }

    @Test
    void dailySeriesWithZeroFill() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        for (int i = 0; i < 3; i++) seed(code, "2026-11-01T1" + i + ":00:00Z", null, UserAgentCategory.BROWSER);
        for (int i = 0; i < 2; i++) seed(code, "2026-11-03T1" + i + ":00:00Z", null, UserAgentCategory.BROWSER);

        mvc.perform(get(url(code)).param("from", "2026-11-01").param("to", "2026-11-03"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.from").value("2026-11-01"))
                .andExpect(jsonPath("$.to").value("2026-11-03"))
                .andExpect(jsonPath("$.excludeBots").value(false))
                .andExpect(jsonPath("$.totalClicks").value(5))
                .andExpect(jsonPath("$.daily.length()").value(3))
                .andExpect(jsonPath("$.daily[0].date").value("2026-11-01"))
                .andExpect(jsonPath("$.daily[0].clicks").value(3))
                .andExpect(jsonPath("$.daily[1].date").value("2026-11-02"))
                .andExpect(jsonPath("$.daily[1].clicks").value(0))
                .andExpect(jsonPath("$.daily[2].date").value("2026-11-03"))
                .andExpect(jsonPath("$.daily[2].clicks").value(2));
    }

    @Test
    void topReferrersAreHostsOrderedByCount() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        seed(code, "2026-11-02T10:00:00Z", "b.com", UserAgentCategory.BROWSER);
        seed(code, "2026-11-02T10:01:00Z", "a.com", UserAgentCategory.BROWSER);
        seed(code, "2026-11-02T10:02:00Z", "a.com", UserAgentCategory.BROWSER);

        String body = mvc.perform(get(url(code)).param("from", "2026-11-01").param("to", "2026-11-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topReferrers.length()").value(2))
                .andExpect(jsonPath("$.topReferrers[0].host").value("a.com"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(2))
                .andExpect(jsonPath("$.topReferrers[1].host").value("b.com"))
                .andExpect(jsonPath("$.topReferrers[1].clicks").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("http://").doesNotContain("https://");
    }

    @Test
    void excludeBotsChangesTotal() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        seed(code, "2026-11-02T10:00:00Z", null, UserAgentCategory.BROWSER);
        seed(code, "2026-11-02T11:00:00Z", null, UserAgentCategory.BROWSER);
        seed(code, "2026-11-02T12:00:00Z", null, UserAgentCategory.BOT);

        mvc.perform(get(url(code)).param("from", "2026-11-02").param("to", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(3))
                .andExpect(jsonPath("$.userAgents.BROWSER").value(2))
                .andExpect(jsonPath("$.userAgents.BOT").value(1))
                .andExpect(jsonPath("$.userAgents.OTHER").value(0));

        mvc.perform(get(url(code)).param("from", "2026-11-02").param("to", "2026-11-02").param("excludeBots", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludeBots").value(true))
                .andExpect(jsonPath("$.totalClicks").value(2));
    }

    @Test
    void defaultRangeIsReturnedWhenParamsOmitted() throws Exception {
        String code = TestLinkSupport.createLink(mvc);

        mvc.perform(get(url(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily.length()").value(30))
                .andExpect(jsonPath("$.totalClicks").value(0));
    }

    @Test
    void unknownCodeIs404WithJsonErrorBody() throws Exception {
        String body = mvc.perform(get(url("zzNoSuch9")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isNotBlank();
    }

    @Test
    void fromAfterToIs400() throws Exception {
        String code = TestLinkSupport.createLink(mvc);

        String body = mvc.perform(get(url(code)).param("from", "2026-11-03").param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isNotBlank();
    }

    @Test
    void invalidMonthIs400() throws Exception {
        String code = TestLinkSupport.createLink(mvc);

        mvc.perform(get(url(code)).param("from", "2026-13-01").param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void garbageDateIs400() throws Exception {
        String code = TestLinkSupport.createLink(mvc);

        mvc.perform(get(url(code)).param("to", "yesterday")).andExpect(status().isBadRequest());
    }

    @Test
    void nonBooleanExcludeBotsIs400() throws Exception {
        String code = TestLinkSupport.createLink(mvc);

        mvc.perform(get(url(code)).param("excludeBots", "maybe")).andExpect(status().isBadRequest());
    }

    @Test
    void rangeOverMaximumIs400() throws Exception {
        String code = TestLinkSupport.createLink(mvc);

        mvc.perform(get(url(code)).param("from", "2025-01-01").param("to", "2026-11-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredLinkStillServesAnalytics() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        seed(code, "2026-11-02T10:00:00Z", null, UserAgentCategory.BROWSER);
        TestLinkSupport.expire(jdbc, code);

        mvc.perform(get(url(code)).param("from", "2026-11-02").param("to", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1));
    }
}

package com.veenaacharya.shortener.analytics;

import com.veenaacharya.shortener.support.TestLinkSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analyticsservice;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "app.rate-limit.requests=1000"
})
@AutoConfigureMockMvc
@Import(AnalyticsServiceTest.FixedClockConfig.class)
class AnalyticsServiceTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired AnalyticsService service;
    @Autowired ClickEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private long newLink(String[] codeOut) throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        codeOut[0] = code;
        return TestLinkSupport.linkId(jdbc, code);
    }

    private void add(long linkId, String iso, String host, UserAgentCategory ua) {
        events.save(new ClickEvent(null, linkId, Instant.parse(iso), host, ua));
    }

    @Test
    void dailySeriesIsZeroFilledAndTotalCoversRangeOnly() throws Exception {
        String[] c = new String[1];
        long id = newLink(c);
        add(id, "2026-10-31T23:59:59Z", null, UserAgentCategory.BROWSER); // before range
        add(id, "2026-11-01T00:00:00Z", null, UserAgentCategory.BROWSER);
        add(id, "2026-11-01T12:00:00Z", null, UserAgentCategory.BROWSER);
        add(id, "2026-11-01T23:59:59Z", null, UserAgentCategory.BROWSER);
        add(id, "2026-11-03T08:00:00Z", null, UserAgentCategory.BROWSER);
        add(id, "2026-11-03T09:00:00Z", null, UserAgentCategory.BROWSER);
        add(id, "2026-11-04T00:00:00Z", null, UserAgentCategory.BROWSER); // after range

        LinkAnalyticsResponse r = service.getAnalytics(c[0], LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-03"), false);

        assertThat(r.code()).isEqualTo(c[0]);
        assertThat(r.from()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(r.to()).isEqualTo(LocalDate.parse("2026-11-03"));
        assertThat(r.totalClicks()).isEqualTo(5);
        assertThat(r.daily()).containsExactly(
                new DailyClicks(LocalDate.parse("2026-11-01"), 3),
                new DailyClicks(LocalDate.parse("2026-11-02"), 0),
                new DailyClicks(LocalDate.parse("2026-11-03"), 2));
    }

    @Test
    void singleDayRangeReturnsOneEntry() throws Exception {
        String[] c = new String[1];
        long id = newLink(c);
        add(id, "2026-11-05T10:00:00Z", null, UserAgentCategory.OTHER);

        LinkAnalyticsResponse r = service.getAnalytics(c[0], LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-05"), false);

        assertThat(r.daily()).containsExactly(new DailyClicks(LocalDate.parse("2026-11-05"), 1));
        assertThat(r.totalClicks()).isEqualTo(1);
    }

    @Test
    void noEventsGivesZeroTotalsAndEmptyReferrers() throws Exception {
        String[] c = new String[1];
        newLink(c);

        LinkAnalyticsResponse r = service.getAnalytics(c[0], LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02"), false);

        assertThat(r.totalClicks()).isZero();
        assertThat(r.daily()).hasSize(2).allSatisfy(d -> assertThat(d.clicks()).isZero());
        assertThat(r.topReferrers()).isEmpty();
        assertThat(r.userAgents()).containsEntry(UserAgentCategory.BROWSER, 0L)
                .containsEntry(UserAgentCategory.BOT, 0L).containsEntry(UserAgentCategory.OTHER, 0L);
    }

    @Test
    void topReferrersOrderedByCountThenHostAndNullsSkipped() throws Exception {
        String[] c = new String[1];
        long id = newLink(c);
        add(id, "2026-11-02T10:00:00Z", "b.com", UserAgentCategory.BROWSER);
        add(id, "2026-11-02T10:01:00Z", "a.com", UserAgentCategory.BROWSER);
        add(id, "2026-11-02T10:02:00Z", "a.com", UserAgentCategory.BROWSER);
        add(id, "2026-11-02T10:03:00Z", "c.com", UserAgentCategory.BROWSER);
        add(id, "2026-11-02T10:04:00Z", null, UserAgentCategory.BROWSER);

        LinkAnalyticsResponse r = service.getAnalytics(c[0], LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-03"), false);

        assertThat(r.topReferrers()).containsExactly(
                new ReferrerCount("a.com", 2), new ReferrerCount("b.com", 1), new ReferrerCount("c.com", 1));
        assertThat(r.totalClicks()).isEqualTo(5);
    }

    @Test
    void topReferrersLimitedToTen() throws Exception {
        String[] c = new String[1];
        long id = newLink(c);
        for (int i = 0; i < 12; i++) {
            add(id, "2026-11-02T10:%02d:00Z".formatted(i), "h%02d.com".formatted(i), UserAgentCategory.BROWSER);
        }

        LinkAnalyticsResponse r = service.getAnalytics(c[0], LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-03"), false);

        assertThat(r.topReferrers()).hasSize(10);
        assertThat(r.topReferrers().get(0).host()).isEqualTo("h00.com");
        assertThat(r.topReferrers().get(9).host()).isEqualTo("h09.com");
    }

    @Test
    void excludeBotsFiltersTotalsDailyAndReferrers() throws Exception {
        String[] c = new String[1];
        long id = newLink(c);
        add(id, "2026-11-02T10:00:00Z", "a.com", UserAgentCategory.BROWSER);
        add(id, "2026-11-02T11:00:00Z", "a.com", UserAgentCategory.BROWSER);
        add(id, "2026-11-02T12:00:00Z", "bot.com", UserAgentCategory.BOT);

        LinkAnalyticsResponse with = service.getAnalytics(c[0], LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"), false);
        LinkAnalyticsResponse without = service.getAnalytics(c[0], LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"), true);

        assertThat(with.totalClicks()).isEqualTo(3);
        assertThat(with.excludeBots()).isFalse();
        assertThat(with.userAgents()).containsEntry(UserAgentCategory.BROWSER, 2L).containsEntry(UserAgentCategory.BOT, 1L)
                .containsEntry(UserAgentCategory.OTHER, 0L);
        assertThat(without.totalClicks()).isEqualTo(2);
        assertThat(without.excludeBots()).isTrue();
        assertThat(without.daily()).containsExactly(new DailyClicks(LocalDate.parse("2026-11-02"), 2));
        assertThat(without.topReferrers()).containsExactly(new ReferrerCount("a.com", 2));
        assertThat(without.userAgents()).containsEntry(UserAgentCategory.BOT, 0L);
    }

    @Test
    void unknownCodeThrowsNotFound() {
        Throwable t = catchThrowable(() -> service.getAnalytics("zzNoSuch9", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02"), false));

        assertThat(t).isNotNull();
        assertThat(t.getClass().getSimpleName()).isEqualTo("LinkNotFoundException");
    }

    @Test
    void expiredLinkStillReturnsHistory() throws Exception {
        String[] c = new String[1];
        long id = newLink(c);
        add(id, "2026-11-02T10:00:00Z", null, UserAgentCategory.BROWSER);
        TestLinkSupport.expire(jdbc, c[0]);

        LinkAnalyticsResponse r = service.getAnalytics(c[0], LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"), false);

        assertThat(r.totalClicks()).isEqualTo(1);
    }

    @Test
    void fromAfterToIsRejected() throws Exception {
        String[] c = new String[1];
        newLink(c);

        Throwable t = catchThrowable(() -> service.getAnalytics(c[0], LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-01"), false));

        assertThat(t).isNotNull();
        assertThat(t.getClass().getSimpleName()).isEqualTo("InvalidAnalyticsRangeException");
    }

    @Test
    void rangeOf366DaysAllowedAnd367Rejected() throws Exception {
        String[] c = new String[1];
        newLink(c);
        LocalDate to = LocalDate.parse("2026-11-01");

        LinkAnalyticsResponse ok = service.getAnalytics(c[0], to.minusDays(365), to, false);
        Throwable t = catchThrowable(() -> service.getAnalytics(c[0], to.minusDays(366), to, false));

        assertThat(ok.daily()).hasSize(366);
        assertThat(t).isNotNull();
        assertThat(t.getClass().getSimpleName()).isEqualTo("InvalidAnalyticsRangeException");
    }

    @Test
    void defaultsAreLast30DaysEndingTodayUtc() throws Exception {
        String[] c = new String[1];
        long id = newLink(c);
        add(id, "2026-11-09T01:00:00Z", null, UserAgentCategory.BROWSER);
        add(id, "2026-10-11T01:00:00Z", null, UserAgentCategory.BROWSER);
        add(id, "2026-10-10T23:00:00Z", null, UserAgentCategory.BROWSER); // outside the default window

        LinkAnalyticsResponse r = service.getAnalytics(c[0], null, null, false);

        assertThat(r.to()).isEqualTo(LocalDate.parse("2026-11-09"));
        assertThat(r.from()).isEqualTo(LocalDate.parse("2026-10-11"));
        assertThat(r.daily()).hasSize(30);
        assertThat(r.totalClicks()).isEqualTo(2);
    }
}

package com.veenaacharya.shortener.link;

import com.veenaacharya.shortener.analytics.ClickEvent;
import com.veenaacharya.shortener.analytics.ClickEventRepository;
import com.veenaacharya.shortener.analytics.UserAgentCategory;
import com.veenaacharya.shortener.support.TestLinkSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:clickrecording;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "app.rate-limit.requests=1000"
})
@AutoConfigureMockMvc
class LinkServiceClickRecordingTest {

    @Autowired LinkService linkService;
    @Autowired ClickEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private static final Instant FAR_PAST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2100-01-01T00:00:00Z");

    @Test
    void resolveWithMetadataStoresOneEventWithExpectedFields() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        long id = TestLinkSupport.linkId(jdbc, code);
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        linkService.resolve(code, new ClickMetadata("Mozilla/5.0 (X11; Linux) Chrome/126", "https://Ref.Example.com/a?b=1"));

        List<ClickEvent> stored = events.findInRange(id, FAR_PAST, FAR_FUTURE);
        assertThat(stored).hasSize(1);
        ClickEvent e = stored.get(0);
        assertThat(e.linkId()).isEqualTo(id);
        assertThat(e.referrerHost()).isEqualTo("ref.example.com");
        assertThat(e.uaCategory()).isEqualTo(UserAgentCategory.BROWSER);
        assertThat(e.clickedAt()).isBetween(before, Instant.now().plus(1, ChronoUnit.MINUTES));
    }

    @Test
    void missingHeadersGiveNullHostAndOtherCategory() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        long id = TestLinkSupport.linkId(jdbc, code);

        linkService.resolve(code, new ClickMetadata(null, null));

        List<ClickEvent> stored = events.findInRange(id, FAR_PAST, FAR_FUTURE);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).referrerHost()).isNull();
        assertThat(stored.get(0).uaCategory()).isEqualTo(UserAgentCategory.OTHER);
    }

    @Test
    void botUserAgentIsStoredAsBot() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        long id = TestLinkSupport.linkId(jdbc, code);

        linkService.resolve(code, new ClickMetadata("Googlebot/2.1", null));

        assertThat(events.findInRange(id, FAR_PAST, FAR_FUTURE))
                .extracting(ClickEvent::uaCategory).containsExactly(UserAgentCategory.BOT);
    }

    @Test
    void legacyResolveStillWorksAndStoresNoMetadataButCounts() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        long id = TestLinkSupport.linkId(jdbc, code);

        linkService.resolve(code);

        // The overload delegates with ClickMetadata.none(): the click is recorded without personal data.
        List<ClickEvent> stored = events.findInRange(id, FAR_PAST, FAR_FUTURE);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).referrerHost()).isNull();
        assertThat(stored.get(0).uaCategory()).isEqualTo(UserAgentCategory.OTHER);
    }

    @Test
    void clickMetadataNoneHasNullFields() {
        ClickMetadata none = ClickMetadata.none();
        assertThat(none.userAgent()).isNull();
        assertThat(none.referer()).isNull();
    }

    @Test
    void unknownCodeThrowsNotFoundAndStoresNothing() {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM click_event", Long.class);

        Throwable t = catchThrowable(() -> linkService.resolve("zzNoSuch9", new ClickMetadata("Mozilla/5.0", null)));

        assertThat(t).isNotNull();
        assertThat(t.getClass().getSimpleName()).isEqualTo("LinkNotFoundException");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM click_event", Long.class)).isEqualTo(before);
    }

    @Test
    void expiredLinkThrowsExpiredAndStoresNothing() throws Exception {
        String code = TestLinkSupport.createLink(mvc);
        long id = TestLinkSupport.linkId(jdbc, code);
        TestLinkSupport.expire(jdbc, code);

        Throwable t = catchThrowable(() -> linkService.resolve(code, new ClickMetadata("Mozilla/5.0", "https://a.com/x")));

        assertThat(t).isNotNull();
        assertThat(t.getClass().getSimpleName()).isEqualTo("LinkExpiredException");
        assertThat(TestLinkSupport.eventCount(jdbc, id)).isZero();
    }
}

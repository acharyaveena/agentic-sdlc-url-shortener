package com.veenaacharya.shortener.analytics;

import com.veenaacharya.shortener.config.AppProperties;
import com.veenaacharya.shortener.link.Link;
import com.veenaacharya.shortener.link.LinkNotFoundException;
import com.veenaacharya.shortener.link.LinkRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {

    private final LinkRepository links;
    private final ClickEventRepository events;
    private final AppProperties properties;
    private final Clock clock;

    public AnalyticsService(LinkRepository links, ClickEventRepository events,
                            AppProperties properties, Clock clock) {
        this.links = links;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LinkAnalyticsResponse getAnalytics(String code, LocalDate from, LocalDate to, boolean excludeBots) {
        // No expiry check: expired links keep their history.
        Link link = links.findByShortCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        AppProperties.Analytics config = properties.getAnalytics();

        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate start = from != null ? from : end.minusDays(config.getDefaultRangeDays() - 1L);
        if (start.isAfter(end)) {
            throw new InvalidAnalyticsRangeException("'from' must not be later than 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > config.getMaxRangeDays()) {
            throw new InvalidAnalyticsRangeException(
                    "Range must not exceed " + config.getMaxRangeDays() + " days");
        }

        List<ClickEvent> rows = events.findInRange(link.id(),
                start.atStartOfDay().toInstant(ZoneOffset.UTC),
                end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        if (excludeBots) {
            rows = rows.stream().filter(e -> e.uaCategory() != UserAgentCategory.BOT).toList();
        }

        Map<LocalDate, Long> perDay = new HashMap<>();
        Map<String, Long> perHost = new HashMap<>();
        Map<UserAgentCategory, Long> perUa = new EnumMap<>(UserAgentCategory.class);
        for (UserAgentCategory c : UserAgentCategory.values()) {
            perUa.put(c, 0L);
        }
        for (ClickEvent e : rows) {
            perDay.merge(e.clickedAt().atZone(ZoneOffset.UTC).toLocalDate(), 1L, Long::sum);
            if (e.referrerHost() != null) {
                perHost.merge(e.referrerHost(), 1L, Long::sum);
            }
            perUa.merge(e.uaCategory(), 1L, Long::sum);
        }

        List<DailyClicks> daily = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            daily.add(new DailyClicks(d, perDay.getOrDefault(d, 0L)));
        }
        List<ReferrerCount> top = perHost.entrySet().stream()
                .map(en -> new ReferrerCount(en.getKey(), en.getValue()))
                .sorted(Comparator.comparingLong(ReferrerCount::clicks).reversed()
                        .thenComparing(ReferrerCount::host))
                .limit(config.getTopReferrersLimit())
                .toList();

        return new LinkAnalyticsResponse(code, start, end, excludeBots, rows.size(), daily, top, perUa);
    }
}

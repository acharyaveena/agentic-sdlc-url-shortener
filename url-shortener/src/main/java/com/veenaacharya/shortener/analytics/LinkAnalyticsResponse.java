package com.veenaacharya.shortener.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record LinkAnalyticsResponse(
        String code,
        LocalDate from,
        LocalDate to,
        boolean excludeBots,
        long totalClicks,
        List<DailyClicks> daily,
        List<ReferrerCount> topReferrers,
        Map<UserAgentCategory, Long> userAgents) {
}

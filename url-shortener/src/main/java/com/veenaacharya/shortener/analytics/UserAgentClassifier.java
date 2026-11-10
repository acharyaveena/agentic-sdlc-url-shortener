package com.veenaacharya.shortener.analytics;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Coarse, best-effort classification. The raw user agent is never stored. */
@Component
public class UserAgentClassifier {

    private static final List<String> BOT_MARKERS = List.of(
            "bot", "crawler", "spider", "slurp", "curl", "wget", "python-requests", "headless");

    public UserAgentCategory classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UserAgentCategory.OTHER;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        for (String marker : BOT_MARKERS) {
            if (ua.contains(marker)) {
                return UserAgentCategory.BOT;
            }
        }
        return ua.contains("mozilla/") ? UserAgentCategory.BROWSER : UserAgentCategory.OTHER;
    }
}

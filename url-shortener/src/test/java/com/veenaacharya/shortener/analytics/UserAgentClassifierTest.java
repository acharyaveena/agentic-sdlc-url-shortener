package com.veenaacharya.shortener.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentClassifierTest {

    private final UserAgentClassifier classifier = new UserAgentClassifier();

    @Test
    void desktopBrowserIsBrowser() {
        assertThat(classifier.classify("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36"))
                .isEqualTo(UserAgentCategory.BROWSER);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Mozilla/5.0 (compatible; Yahoo! Slurp)",
            "SomeCrawler/1.0",
            "Baiduspider/2.0",
            "curl/8.4.0",
            "Wget/1.21",
            "python-requests/2.31",
            "Mozilla/5.0 HeadlessChrome/120.0",
            "GOOGLEBOT"
    })
    void knownBotsAreBot(String ua) {
        assertThat(classifier.classify(ua)).isEqualTo(UserAgentCategory.BOT);
    }

    @Test
    void botWinsOverMozillaPrefix() {
        assertThat(classifier.classify("Mozilla/5.0 (compatible; bingbot/2.0)")).isEqualTo(UserAgentCategory.BOT);
    }

    @Test
    void nullAndBlankAreOther() {
        assertThat(classifier.classify(null)).isEqualTo(UserAgentCategory.OTHER);
        assertThat(classifier.classify("")).isEqualTo(UserAgentCategory.OTHER);
        assertThat(classifier.classify("   ")).isEqualTo(UserAgentCategory.OTHER);
    }

    @Test
    void unknownClientIsOther() {
        assertThat(classifier.classify("MyCustomClient/1.0")).isEqualTo(UserAgentCategory.OTHER);
    }

    @Test
    void mozillaMatchIsCaseInsensitive() {
        assertThat(classifier.classify("MOZILLA/5.0 (X11; Linux)")).isEqualTo(UserAgentCategory.BROWSER);
    }
}

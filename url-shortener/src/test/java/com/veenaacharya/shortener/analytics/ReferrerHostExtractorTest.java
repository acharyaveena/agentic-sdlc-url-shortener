package com.veenaacharya.shortener.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReferrerHostExtractorTest {

    private final ReferrerHostExtractor extractor = new ReferrerHostExtractor();

    @Test
    void extractsHostOnly() {
        assertThat(extractor.extract("https://a.com/x")).isEqualTo("a.com");
    }

    @Test
    void dropsPathQueryFragmentAndPort() {
        assertThat(extractor.extract("http://b.com:8080/p/q?token=secret#frag")).isEqualTo("b.com");
    }

    @Test
    void lowercasesHost() {
        assertThat(extractor.extract("https://Example.COM/Path")).isEqualTo("example.com");
    }

    @Test
    void keepsSubdomain() {
        assertThat(extractor.extract("https://news.example.org/a")).isEqualTo("news.example.org");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not a url", "ftp://files.example.com/x", "://nohost", "https:///path-only",
            "javascript:alert(1)"})
    void invalidOrUnsupportedIsNull(String input) {
        assertThat(extractor.extract(input)).isNull();
    }

    @Test
    void hostOf253CharsIsKept() {
        String host = "a".repeat(249) + ".com";
        assertThat(host).hasSize(253);
        assertThat(extractor.extract("https://" + host + "/x")).isEqualTo(host);
    }

    @Test
    void hostLongerThan253IsNull() {
        String host = ("a".repeat(60) + ".").repeat(5) + "com";
        assertThat(host.length()).isGreaterThan(253);
        assertThat(extractor.extract("https://" + host + "/x")).isNull();
    }
}

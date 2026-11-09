package com.veenaacharya.shortener.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.shortener.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private static final String PREFIX = "https://example.com/";

    private final UrlValidator validator = new UrlValidator(TestProps.withBaseUrl("https://short.example"));

    private static String urlOfLength(int length) {
        return PREFIX + "a".repeat(length - PREFIX.length());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "mailto:someone@example.com",
            "example.com/page",
            "www.example.com",
            "//example.com/page"
    })
    void rejectsUnsupportedOrMissingScheme(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   \t  "})
    void rejectsBlankUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://exa mple.com/", "http://[::1", "https://", "http:///path"})
    void rejectsMalformedOrHostlessUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsUrlLongerThanMaximum() {
        String url = urlOfLength(2049);
        assertThat(url).hasSize(2049);
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void acceptsUrlOfExactlyMaximumLength() {
        String url = urlOfLength(2048);
        assertThat(url).hasSize(2048);
        assertThat(validator.validate(url)).isEqualTo(url);
    }

    @Test
    void rejectsUserInfo() {
        assertThatThrownBy(() -> validator.validate("https://user:secret@example.com/"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "HTTP://SHORT.EXAMPLE:9999/x",
            "https://short.example/abc",
            "https://Short.Example",
            "http://localhost:8080/x",
            "http://LOCALHOST/x",
            "http://127.0.0.1/x",
            "http://127.0.0.1:9000/x",
            "http://[::1]/x",
            "http://[::1]:8080/x"
    })
    void rejectsSelfReferencingHosts(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("Self-referencing links are not allowed");
    }

    @Test
    void trimsValidInput() {
        assertThat(validator.validate("  https://example.com/page?q=1  ")).isEqualTo("https://example.com/page?q=1");
    }

    @Test
    void acceptsHttpAndHttpsCaseInsensitively() {
        assertThat(validator.validate("http://example.com/a")).isEqualTo("http://example.com/a");
        assertThat(validator.validate("HTTPS://example.com/a")).isEqualTo("HTTPS://example.com/a");
    }

    @Test
    void acceptsHostThatMerelyContainsBlockedName() {
        assertThat(validator.validate("https://notlocalhost.example.org/")).isEqualTo("https://notlocalhost.example.org/");
    }
}

package com.veenaacharya.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.veenaacharya.shortener.code.ShortCodeGenerator;
import com.veenaacharya.shortener.config.AppProperties;
import com.veenaacharya.shortener.support.TestProps;
import com.veenaacharya.shortener.validation.InvalidUrlException;
import com.veenaacharya.shortener.validation.UrlValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    LinkRepository repository;

    @Mock
    ShortCodeGenerator generator;

    @Spy
    AppProperties properties = TestProps.withBaseUrl("https://short.example");

    @Spy
    UrlValidator validator = new UrlValidator(TestProps.withBaseUrl("https://short.example"));

    @Spy
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks
    LinkService service;

    @Test
    void retriesOnCollisionAndSucceedsOnThirdAttempt() {
        when(generator.next()).thenReturn("dup", "dup", "new");
        when(repository.save(any(Link.class)))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenAnswer(inv -> inv.getArgument(0));

        Link result = service.create("https://example.com/page");

        assertThat(result.shortCode()).isEqualTo("new");
        assertThat(result.longUrl()).isEqualTo("https://example.com/page");
        assertThat(result.clickCount()).isZero();
        verify(repository, times(3)).save(any(Link.class));
        verify(generator, times(3)).next();
    }

    @Test
    void failsAfterExactlyFiveCollidingAttempts() {
        when(generator.next()).thenReturn("dup");
        when(repository.save(any(Link.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.create("https://example.com/page"))
                .isInstanceOf(CodeGenerationExhaustedException.class);

        verify(repository, times(5)).save(any(Link.class));
    }

    @Test
    void invalidUrlIsRejectedBeforeAnyCodeIsGeneratedOrSaved() {
        assertThatThrownBy(() -> service.create("ftp://example.com/x")).isInstanceOf(InvalidUrlException.class);

        verify(generator, never()).next();
        verify(repository, never()).save(any(Link.class));
    }

    @Test
    void resolveThrowsNotFoundWhenNoRowWasIncremented() {
        when(repository.incrementClickCount("missing")).thenReturn(0);

        assertThatThrownBy(() -> service.resolve("missing")).isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void resolveIncrementsOnceAndReturnsLongUrl() {
        when(repository.incrementClickCount("abc1234")).thenReturn(1);
        when(repository.findByShortCode("abc1234"))
                .thenReturn(Optional.of(new Link(1L, "abc1234", "https://example.com/a", 1L, NOW)));

        assertThat(service.resolve("abc1234")).isEqualTo("https://example.com/a");
        verify(repository, times(1)).incrementClickCount("abc1234");
    }

    @Test
    void statsReturnsStoredLink() {
        Link link = new Link(1L, "abc1234", "https://example.com/a", 7L, NOW);
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(link));

        Link result = service.stats("abc1234");

        assertThat(result.clickCount()).isEqualTo(7L);
        assertThat(result.longUrl()).isEqualTo("https://example.com/a");
    }

    @Test
    void statsThrowsNotFoundForUnknownCode() {
        when(repository.findByShortCode("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stats("nope")).isInstanceOf(LinkNotFoundException.class);
    }
}

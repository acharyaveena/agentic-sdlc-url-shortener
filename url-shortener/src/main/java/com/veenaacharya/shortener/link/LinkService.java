package com.veenaacharya.shortener.link;

import com.veenaacharya.shortener.analytics.ClickEvent;
import com.veenaacharya.shortener.analytics.ClickEventRepository;
import com.veenaacharya.shortener.analytics.ReferrerHostExtractor;
import com.veenaacharya.shortener.analytics.UserAgentClassifier;
import com.veenaacharya.shortener.code.ShortCodeGenerator;
import com.veenaacharya.shortener.config.AppProperties;
import com.veenaacharya.shortener.validation.UrlValidator;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {

    private final LinkRepository repository;
    private final ShortCodeGenerator generator;
    private final UrlValidator validator;
    private final AppProperties properties;
    private final Clock clock;
    private final ClickEventRepository clickEvents;
    private final UserAgentClassifier userAgentClassifier;
    private final ReferrerHostExtractor referrerHostExtractor;

    public LinkService(LinkRepository repository, ShortCodeGenerator generator,
                       UrlValidator validator, AppProperties properties, Clock clock,
                       ClickEventRepository clickEvents, UserAgentClassifier userAgentClassifier,
                       ReferrerHostExtractor referrerHostExtractor) {
        this.repository = repository;
        this.generator = generator;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
        this.clickEvents = clickEvents;
        this.userAgentClassifier = userAgentClassifier;
        this.referrerHostExtractor = referrerHostExtractor;
    }

    // Not transactional: each insert must commit or fail on its own so a collision can be retried.
    public Link create(String rawUrl, Long ttlSeconds) {
        String url = validator.validate(rawUrl);
        Instant now = Instant.now(clock);
        Instant expiresAt = ttlSeconds != null
                ? now.plusSeconds(ttlSeconds)
                : now.plus(properties.getLink().getDefaultTtl());
        int attempts = properties.getMaxCodeAttempts();
        for (int i = 0; i < attempts; i++) {
            Link candidate = new Link(null, generator.next(), url, 0L, now, expiresAt);
            try {
                return repository.save(candidate);
            } catch (RuntimeException e) {
                if (!isDuplicateKey(e)) {
                    throw e;
                }
            }
        }
        throw new CodeGenerationExhaustedException(attempts);
    }

    @Transactional
    public String resolve(String code) {
        return resolve(code, ClickMetadata.none());
    }

    // Click events are recorded in the same transaction as the counter increment: a failed insert
    // fails the redirect rather than silently losing analytics data.
    @Transactional
    public String resolve(String code, ClickMetadata metadata) {
        Link link = repository.findByShortCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        if (link.isExpired(Instant.now(clock))) {
            throw new LinkExpiredException(code);
        }
        if (repository.incrementClickCount(code) == 0) {
            throw new LinkNotFoundException(code);
        }
        clickEvents.save(new ClickEvent(null, link.id(), Instant.now(clock),
                referrerHostExtractor.extract(metadata.referer()), userAgentClassifier.classify(metadata.userAgent())));
        return link.longUrl();
    }

    @Transactional(readOnly = true)
    public Link stats(String code) {
        return repository.findByShortCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    private static boolean isDuplicateKey(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}

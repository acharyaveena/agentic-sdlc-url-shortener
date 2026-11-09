package com.veenaacharya.shortener.link;

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

    public LinkService(LinkRepository repository, ShortCodeGenerator generator,
                       UrlValidator validator, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
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
        Link link = repository.findByShortCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        if (link.isExpired(Instant.now(clock))) {
            throw new LinkExpiredException(code);
        }
        if (repository.incrementClickCount(code) == 0) {
            throw new LinkNotFoundException(code);
        }
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

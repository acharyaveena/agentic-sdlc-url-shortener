package com.veenaacharya.shortener.link;

import com.veenaacharya.shortener.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically deletes links that expired more than the configured grace period ago. */
@Component
public class ExpiredLinkCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredLinkCleanupJob.class);

    private final LinkRepository repository;
    private final AppProperties properties;
    private final Clock clock;

    public ExpiredLinkCleanupJob(LinkRepository repository, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.link.cleanup-interval:PT1H}")
    public void purge() {
        Instant cutoff = clock.instant().minus(properties.getLink().getCleanupGrace());
        int deleted = repository.deleteExpiredBefore(cutoff);
        log.info("Expired link cleanup removed {} links older than {}", deleted, cutoff);
    }
}

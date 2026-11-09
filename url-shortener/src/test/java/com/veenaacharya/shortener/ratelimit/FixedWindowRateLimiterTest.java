package com.veenaacharya.shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.shortener.support.MutableClock;
import com.veenaacharya.shortener.support.TestProps;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    private MutableClock clock;
    private FixedWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        limiter = new FixedWindowRateLimiter(
                TestProps.app(Map.of("app.rate-limit.requests", "10", "app.rate-limit.window", "PT1M")), clock);
    }

    @Test
    void allowsTenThenDeniesEleventhWithRetryAfter() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).as("request %d", i + 1).isTrue();
        }
        FixedWindowRateLimiter.Decision denied = limiter.tryAcquire("a");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void retryAfterShrinksAsTimePasses() {
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("a");
        }
        clock.advance(Duration.ofSeconds(30));

        FixedWindowRateLimiter.Decision denied = limiter.tryAcquire("a");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(30L);
    }

    @Test
    void otherKeyIsIndependent() {
        for (int i = 0; i < 11; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void allowedAgainAfterWindowPasses() {
        for (int i = 0; i < 11; i++) {
            limiter.tryAcquire("a");
        }
        clock.advance(Duration.ofSeconds(60));

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void exactlyTenOfTwentyConcurrentRequestsAreAllowed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(20);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire("shared").allowed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(allowed.get()).isEqualTo(10);
    }
}

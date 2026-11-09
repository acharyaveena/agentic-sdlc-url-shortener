package com.veenaacharya.shortener.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class FixedWindowRateLimiter {

    private static final int CLEANUP_THRESHOLD = 1000;

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(Instant start, int count) {
    }

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    public Decision tryAcquire(String clientKey) {
        Instant now = clock.instant();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(e -> !now.isBefore(e.getValue().start().plus(window)));
        }
        Window updated = windows.compute(clientKey, (k, current) -> {
            if (current == null || !now.isBefore(current.start().plus(window))) {
                return new Window(now, 1);
            }
            return new Window(current.start(), current.count() + 1);
        });
        if (updated.count() <= limit) {
            return new Decision(true, 0);
        }
        Duration remaining = Duration.between(now, updated.start().plus(window));
        long seconds = remaining.toSeconds() + (remaining.toNanosPart() > 0 ? 1 : 0);
        return new Decision(false, Math.max(1, seconds));
    }
}

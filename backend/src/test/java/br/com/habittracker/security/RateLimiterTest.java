package br.com.habittracker.security;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    static final class MutableClock extends Clock {
        Instant current = Instant.parse("2026-10-15T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return current; }
    }

    @Test void limitsAttemptsWithoutExtendingTheLockoutOnEveryRetry() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock, 4);
        Duration window = Duration.ofMinutes(15);
        assertEquals(0, limiter.retryAfter("a", 2, window));
        assertEquals(0, limiter.retryAfter("a", 2, window));
        assertEquals(900, limiter.retryAfter("a", 2, window));
        clock.current = clock.current.plusSeconds(899);
        assertEquals(1, limiter.retryAfter("a", 2, window));
        clock.current = clock.current.plusSeconds(1);
        assertEquals(0, limiter.retryAfter("a", 2, window));
    }

    @Test void newKeysCannotEvictAnActiveLimitWhenMemoryIsFull() {
        RateLimiter limiter = new RateLimiter(Clock.systemUTC(), 1);
        assertEquals(0, limiter.retryAfter("a", 1, Duration.ofMinutes(1)));
        assertTrue(limiter.retryAfter("b", 1, Duration.ofMinutes(1)) > 0);
        assertTrue(limiter.retryAfter("a", 1, Duration.ofMinutes(1)) > 0);
    }
}

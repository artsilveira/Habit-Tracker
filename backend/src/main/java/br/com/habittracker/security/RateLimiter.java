package br.com.habittracker.security;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Limites por janela para a instância local, com memória limitada e relógio testável. */
public final class RateLimiter {
    private record Bucket(int attempts, long expiresAt) {}
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final Clock clock;
    private final int capacity;

    public RateLimiter(Clock clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
    }

    /** Zero libera a tentativa; valor positivo informa quantos segundos esperar. */
    public synchronized long retryAfter(String key, int limit, Duration window) {
        long now = clock.millis();
        buckets.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        Bucket current = buckets.get(key);
        if (current == null) {
            // Não descartar buckets ativos: isso permitiria contornar o limite com chaves novas.
            if (buckets.size() >= capacity) return Math.max(1, window.toSeconds());
            buckets.put(key, new Bucket(1, now + window.toMillis()));
            return 0;
        }
        if (current.attempts >= limit) return Math.max(1, (current.expiresAt - now + 999) / 1000);
        buckets.put(key, new Bucket(current.attempts + 1, current.expiresAt));
        return 0;
    }
}

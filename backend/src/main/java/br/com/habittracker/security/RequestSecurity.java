package br.com.habittracker.security;

import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;

public final class RequestSecurity {
    private final String origin;
    private final boolean secureCookies;
    private final RateLimiter limiter = new RateLimiter(Clock.systemUTC(), 4096);

    public RequestSecurity(String origin, boolean secureCookies) {
        URI uri = URI.create(origin);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("APP_ORIGIN deve conter apenas a origem, sem caminho ou barra final.");
        }
        if (uri.getScheme().equals("https") && !secureCookies) {
            throw new IllegalArgumentException("HTTPS exige COOKIE_SECURE=true.");
        }
        if (uri.getScheme().equals("http") && !Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())) {
            throw new IllegalArgumentException("Use HTTPS fora do ambiente local.");
        }
        this.origin = origin;
        this.secureCookies = secureCookies;
    }

    public static RequestSecurity fromEnvironment() {
        String origin = System.getenv().getOrDefault("APP_ORIGIN", "http://localhost:5173");
        String secure = System.getenv().getOrDefault("COOKIE_SECURE", String.valueOf(origin.startsWith("https://")));
        if (!secure.equals("true") && !secure.equals("false")) throw new IllegalArgumentException("COOKIE_SECURE deve ser true ou false.");
        return new RequestSecurity(origin, Boolean.parseBoolean(secure));
    }

    public boolean allows(HttpExchange exchange) {
        if (Set.of("GET", "HEAD", "OPTIONS").contains(exchange.getRequestMethod())) return true;
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        return (requestOrigin == null || requestOrigin.equals(origin))
                && !"cross-site".equals(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"));
    }

    public long limitAuth(String ip, String accountHash, boolean register) {
        // Nunca confiar em X-Forwarded-For fornecido pelo cliente.
        long ipWait = limiter.retryAfter((register ? "register:" : "login:") + ip,
                register ? 10 : 30, Duration.ofMinutes(15));
        if (ipWait > 0) return ipWait;
        return limiter.retryAfter("account:" + accountHash, 10, Duration.ofMinutes(15));
    }

    public String sessionCookie(String token, int maxAge) {
        return "habit_session=" + token + "; Path=/api; HttpOnly; SameSite=Strict; Max-Age="
                + maxAge + (secureCookies ? "; Secure" : "");
    }

    public static void headers(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    }
}

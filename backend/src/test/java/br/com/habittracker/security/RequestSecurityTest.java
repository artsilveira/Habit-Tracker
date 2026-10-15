package br.com.habittracker.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestSecurityTest {
    @Test void productionOriginRequiresHttpsAndSecureCookies() {
        assertThrows(IllegalArgumentException.class, () -> new RequestSecurity("http://example.com", false));
        assertThrows(IllegalArgumentException.class, () -> new RequestSecurity("https://example.com", false));
        assertThrows(IllegalArgumentException.class, () -> new RequestSecurity("https://example.com/path", true));
        assertDoesNotThrow(() -> new RequestSecurity("https://example.com", true));
    }

    @Test void cookieFlagsAreExplicitAndLogoutExpiresTheSameCookie() {
        RequestSecurity secure = new RequestSecurity("https://example.com", true);
        assertTrue(secure.sessionCookie("token", 60).contains("; HttpOnly; SameSite=Strict;"));
        assertTrue(secure.sessionCookie("token", 60).endsWith("; Secure"));
        assertTrue(secure.sessionCookie("", 0).contains("Max-Age=0"));
        assertFalse(new RequestSecurity("http://localhost:5173", false).sessionCookie("token", 60).contains("; Secure"));
    }
}

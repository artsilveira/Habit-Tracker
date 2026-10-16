package br.com.habittracker.security;

import java.util.Map;
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

    @Test void renderUsesItsHttpsOriginAndRejectsInsecureConfiguration() {
        RequestSecurity secure = RequestSecurity.fromEnvironment(Map.of(
                "RENDER", "true", "RENDER_EXTERNAL_URL", "https://habit-tracker.onrender.com"));
        assertTrue(secure.sessionCookie("token", 60).endsWith("; Secure"));
        assertThrows(IllegalArgumentException.class, () -> RequestSecurity.fromEnvironment(Map.of("RENDER", "true")));
        assertThrows(IllegalArgumentException.class, () -> RequestSecurity.fromEnvironment(Map.of(
                "RENDER", "true", "RENDER_EXTERNAL_URL", "https://habit-tracker.onrender.com", "COOKIE_SECURE", "false")));
    }

    @Test void proxyLimitsAreSharedEvenWhenTheReportedIpChanges() {
        RequestSecurity secure = new RequestSecurity("https://example.com", true, true);
        for (int index = 0; index < 30; index++) {
            assertEquals(0, secure.limitAuth("ip-" + index, "account-" + index, true));
        }
        assertTrue(secure.limitAuth("another-ip", "another-account", true) > 0);
        assertEquals(0, secure.limitAuth("another-ip", "login-account", false));
        for (int index = 1; index < 10; index++) {
            assertEquals(0, secure.limitAuth("ip-" + index, "login-account", false));
        }
        assertTrue(secure.limitAuth("new-ip", "login-account", false) > 0);
    }
}

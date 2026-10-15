package br.com.habittracker.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordsTest {
    @Test void saltsAreUniqueAndVerificationRejectsWrongPasswords() {
        String hash = Passwords.hash("senha de exemplo");
        assertNotEquals(hash, Passwords.hash("senha de exemplo"));
        assertTrue(Passwords.verify("senha de exemplo", hash));
        assertFalse(Passwords.verify("senha diferente", hash));
        assertFalse(Passwords.verify("senha de exemplo", null));
        assertFalse(Passwords.verify("senha de exemplo", "hash-invalido"));
        assertFalse(Passwords.verify("senha de exemplo", "pbkdf2-sha256$2147483647$AAAA$AAAA"));
    }
}
